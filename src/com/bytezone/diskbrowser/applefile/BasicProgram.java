package com.bytezone.diskbrowser.applefile;

import java.util.*;

import com.bytezone.diskbrowser.HexFormatter;

public class BasicProgram extends AbstractFile
{
  private static final byte ASCII_QUOTE = 0x22;
  private static final byte ASCII_COLON = 0x3A;
  private static final byte ASCII_SEMI_COLON = 0x3B;

  private static final byte TOKEN_FOR = (byte) 0x81;
  private static final byte TOKEN_NEXT = (byte) 0x82;
  private static final byte TOKEN_LET = (byte) 0xAA;
  private static final byte TOKEN_GOTO = (byte) 0xAB;
  private static final byte TOKEN_IF = (byte) 0xAD;
  private static final byte TOKEN_GOSUB = (byte) 0xB0;
  private static final byte TOKEN_REM = (byte) 0xB2;
  private static final byte TOKEN_PRINT = (byte) 0xBA;
  private static final byte TOKEN_THEN = (byte) 0xC4;
  private static final byte TOKEN_EQUALS = (byte) 0xD0;

  private final List<SourceLine> sourceLines = new ArrayList<SourceLine> ();
  private final int endPtr;
  private final Set<Integer> gotoLines = new HashSet<Integer> ();
  private final Set<Integer> gosubLines = new HashSet<Integer> ();

  boolean splitRem = false; // should be a user preference
  boolean alignAssign = true; // should be a user preference
  boolean showTargets = true; // should be a user preference
  boolean showHeader = true; // should be a user preference
  boolean onlyShowTargetLineNumbers = false; // should be a user preference
  int wrapPrintAt = 40;
  int wrapRemAt = 60;

  public BasicProgram (String name, byte[] buffer)
  {
    super (name, buffer);

    int ptr = 0;
    int prevOffset = 0;

    int max = buffer.length - 4;    // need at least 4 bytes to make a SourceLine
    while (ptr < max)
    {
      int offset = HexFormatter.intValue (buffer[ptr], buffer[ptr + 1]);
      if (offset <= prevOffset)
        break;

      SourceLine line = new SourceLine (ptr);
      sourceLines.add (line);
      ptr += line.length;
      prevOffset = offset;
    }
    endPtr = ptr;
  }

  @Override
  public String getText ()
  {
    StringBuilder fullText = new StringBuilder ();
    Stack<String> loopVariables = new Stack<String> ();
    if (showHeader)
      addHeader (fullText);
    int alignPos = 0;
    StringBuilder text;
    int baseOffset = showTargets ? 12 : 8;

    for (SourceLine line : sourceLines)
    {
      text = new StringBuilder (getBase (line) + "  ");

      int indent = loopVariables.size (); // each full line starts at the loop indent
      int ifIndent = 0; // IF statement(s) limit back indentation by NEXT

      for (SubLine subline : line.sublines)
      {
        // Allow empty statements (caused by a single colon)
        if (subline.isEmpty ())
          continue;

        // A REM statement might conceal an assembler routine - see P.CREATE on Diags2E.DSK
        if (subline.is (TOKEN_REM) && subline.containsToken ())
        {
          int address = subline.getAddress () + 1; // skip the REM token
          fullText.append (text
                + String.format ("REM - Inline assembler @ $%02X (%d)%n", address, address));
          String padding = "                         ".substring (0, text.length () + 2);
          for (String asm : subline.getAssembler ())
            fullText.append (padding + asm + "\n");
          continue;
        }

        // Reduce the indent by each NEXT, but only as far as the IF indent allows
        if (subline.is (TOKEN_NEXT))
        {
          popLoopVariables (loopVariables, subline);
          indent = Math.max (ifIndent, loopVariables.size ());
        }

        // Are we joining REM lines with the previous subline?
        if (!splitRem && subline.isJoinableRem ())
        {
          // Join this REM statement to the previous line, so no indenting
          fullText.deleteCharAt (fullText.length () - 1); // remove newline
          fullText.append (" ");
        }
        // ... otherwise do all the indenting and showing of targets etc.
        else
        {
          // Prepare target indicators for subsequent sublines (ie no line number)
          if (showTargets && !subline.isFirst ())
            if (subline.is (TOKEN_GOSUB))
              text.append ("<<--");
            else if (subline.is (TOKEN_GOTO) || subline.isImpliedGoto ())
              text.append (" <--");

          // Align assign statements if required
          if (alignAssign)
            alignPos = alignEqualsPosition (subline, alignPos);

          int column = indent * 2 + baseOffset;
          while (text.length () < column)
            text.append (" ");
        }

        // Add the current text, then reset it
        int pos = subline.is (TOKEN_REM) ? 0 : alignPos;
        String lineText = subline.getAlignedText (pos);

        // if (subline.is (TOKEN_REM) && lineText.length () > wrapRemAt + 4)
        // {
        // System.out.println (subline.getAlignedText (pos));
        // String copy = lineText.substring (4);
        // text.append ("REM ");
        // int inset = text.length ();
        // System.out.println (inset);
        // List<String> remarks = splitRemark (copy, wrapRemAt);
        // for (String remark : remarks)
        // text.append ("                        ".substring (0, inset) + remark);
        // }
        // else
        text.append (lineText);

        // Check for a wrapable PRINT statement (see FROM MACHINE LANGUAGE TO BASIC on DOSToolkit2eB.dsk)
        if (subline.is (TOKEN_PRINT) && wrapPrintAt > 0 && countChars (text, ASCII_QUOTE) == 2
              && countChars (text, ASCII_SEMI_COLON) == 0)
        {
          int first = text.indexOf ("\"");
          int last = text.indexOf ("\"", first + 1);
          if ((last - first) > wrapPrintAt)
          {
            int ptr = first + wrapPrintAt;
            do
            {
              fullText.append (text.substring (0, ptr)
                    + "\n                                 ".substring (0, first + 1));
              text.delete (0, ptr);
              ptr = wrapPrintAt;
            } while (text.length () > wrapPrintAt);
          }
        }

        fullText.append (text + "\n");
        text.setLength (0);

        // Calculate indent changes that take effect after the current subline
        if (subline.is (TOKEN_IF))
          ifIndent = ++indent;
        else if (subline.is (TOKEN_FOR))
        {
          loopVariables.push (subline.forVariable);
          ++indent;
        }
      }

      // Reset the alignment value if we just left an IF - the indentation will be different now.
      if (ifIndent > 0)
        alignPos = 0;
    }

    fullText.deleteCharAt (fullText.length () - 1); // remove last newline
    return fullText.toString ();
  }

  private List<String> splitRemark (String remark, int wrapLength)
  {
    List<String> remarks = new ArrayList<String> ();
    while (remark.length () > wrapLength)
    {
      int max = Math.min (wrapLength, remark.length () - 1);
      while (max > 0 && remark.charAt (max) != ' ')
        --max;
      System.out.println (remark.substring (0, max));
      remarks.add (remark.substring (0, max) + "\n");
      if (max == 0)
        break;
      remark = remark.substring (max + 1);
    }
    remarks.add (remark);
    System.out.println (remark);
    return remarks;
  }

  private int countChars (StringBuilder text, byte ch)
  {
    int total = 0;
    for (int i = 0; i < text.length (); i++)
      if (text.charAt (i) == ch)
        total++;
    return total;
  }

  private String getBase (SourceLine line)
  {
    if (!showTargets)
      return String.format (" %5d", line.lineNumber);

    String lineNumberText = String.format ("%5d", line.lineNumber);
    SubLine subline = line.sublines.get (0);
    String c1 = "  ", c2 = "  ";
    if (subline.is (TOKEN_GOSUB))
      c1 = "<<";
    if (subline.is (TOKEN_GOTO))
      c1 = " <";
    if (gotoLines.contains (line.lineNumber))
      c2 = "> ";
    if (gosubLines.contains (line.lineNumber))
      c2 = ">>";
    if (c1.equals ("  ") && !c2.equals ("  "))
      c1 = "--";
    if (!c1.equals ("  ") && c2.equals ("  "))
      c2 = "--";
    if (onlyShowTargetLineNumbers && !c2.startsWith (">"))
      lineNumberText = "";
    return String.format ("%s%s %s", c1, c2, lineNumberText);
  }

  // Decide whether the current subline needs to be aligned on its equals sign. If so,
  // and the column hasn't been calculated, read ahead to find the highest position.
  private int alignEqualsPosition (SubLine subline, int currentAlignPosition)
  {
    if (subline.assignEqualPos > 0) // does the line have an equals sign?
    {
      if (currentAlignPosition == 0)
        currentAlignPosition = findHighest (subline); // examine following sublines for alignment
      return currentAlignPosition;
    }
    return 0; // reset it
  }

  // The IF processing is so that any assignment that is being aligned doesn't continue
  // to the next full line (because the indentation has changed).
  private int findHighest (SubLine startSubline)
  {
    boolean started = false;
    int highestAssign = startSubline.assignEqualPos;
    fast: for (SourceLine line : sourceLines)
    {
      boolean inIf = false;
      for (SubLine subline : line.sublines)
      {
        if (started)
        {
          // Stop when we come to a line without an equals sign (except for non-split REMs).
          // Lines that start with a REM always break.
          if (subline.assignEqualPos == 0
          // && (splitRem || !subline.is (TOKEN_REM) || subline.isFirst ()))
                && (splitRem || !subline.isJoinableRem ()))
            break fast; // of champions

          if (subline.assignEqualPos > highestAssign)
            highestAssign = subline.assignEqualPos;
        }
        else if (subline == startSubline)
          started = true;
        else if (subline.is (TOKEN_IF))
          inIf = true;
      }
      if (started && inIf)
        break;
    }
    return highestAssign;
  }

  @Override
  public String getHexDump ()
  {
    if (buffer.length < 2)
      return super.getHexDump ();

    StringBuilder pgm = new StringBuilder ();
    if (showHeader)
      addHeader (pgm);

    int ptr = 0;
    int offset = HexFormatter.intValue (buffer[0], buffer[1]);
    int programLoadAddress = offset - getLineLength (0);

    while (ptr <= endPtr) // stop at the same place as the source listing
    {
      int length = getLineLength (ptr);
      if (length == 0)
      {
        pgm.append (HexFormatter.formatNoHeader (buffer, ptr, 2, programLoadAddress));
        ptr += 2;
        break;
      }

      if (ptr + length < buffer.length)
        pgm.append (HexFormatter.formatNoHeader (buffer, ptr, length, programLoadAddress)
              + "\n\n");
      ptr += length;
    }

    if (ptr < buffer.length)
    {
      int length = buffer.length - ptr;
      pgm.append ("\n\n");
      pgm.append (HexFormatter.formatNoHeader (buffer, ptr, length, programLoadAddress));
    }

    return pgm.toString ();
  }

  private void addHeader (StringBuilder pgm)
  {
    pgm.append ("Name    : " + name + "\n");
    pgm.append ("Length  : $" + HexFormatter.format4 (buffer.length));
    pgm.append (" (" + buffer.length + ")\n");

    int programLoadAddress = getLoadAddress ();
    pgm.append ("Load at : $" + HexFormatter.format4 (programLoadAddress));
    pgm.append (" (" + programLoadAddress + ")\n\n");
  }

  private int getLoadAddress ()
  {
    int programLoadAddress = 0;
    if (buffer.length > 1)
    {
      int offset = HexFormatter.intValue (buffer[0], buffer[1]);
      programLoadAddress = offset - getLineLength (0);
    }
    return programLoadAddress;
  }

  private int getLineLength (int ptr)
  {
    int offset = HexFormatter.intValue (buffer[ptr], buffer[ptr + 1]);
    if (offset == 0)
      return 0;
    ptr += 4;
    int length = 5;

    while (ptr < buffer.length && buffer[ptr++] != 0)
      length++;

    return length;
  }

  private void popLoopVariables (Stack<String> loopVariables, SubLine subline)
  {
    if (subline.nextVariables.length == 0) // naked NEXT
    {
      if (loopVariables.size () > 0)
        loopVariables.pop ();
    }
    else
      for (String variable : subline.nextVariables)
        // e.g. NEXT X,Y,Z
        while (loopVariables.size () > 0)
          if (sameVariable (variable, loopVariables.pop ()))
            break;
  }

  private boolean sameVariable (String v1, String v2)
  {
    if (v1.equals (v2))
      return true;
    if (v1.length () >= 2 && v2.length () >= 2 && v1.charAt (0) == v2.charAt (0)
          && v1.charAt (1) == v2.charAt (1))
      return true;
    return false;
  }

  private class SourceLine
  {
    List<SubLine> sublines = new ArrayList<SubLine> ();
    int lineNumber;
    int linePtr;
    int length;

    public SourceLine (int ptr)
    {
      linePtr = ptr;
      lineNumber = HexFormatter.intValue (buffer[ptr + 2], buffer[ptr + 3]);

      int startPtr = ptr += 4;
      boolean inString = false; // can toggle
      boolean inRemark = false; // can only go false -> true
      byte b;

      while ((b = buffer[ptr++]) != 0)
      {
        switch (b)
        {
        // break IF statements into two sublines (allows for easier line indenting)
          case TOKEN_IF:
            if (!inString && !inRemark)
            {
              // skip to THEN or GOTO - if not found then it's an error
              while (buffer[ptr] != TOKEN_THEN && buffer[ptr] != TOKEN_GOTO
                    && buffer[ptr] != 0)
                ptr++;

              // keep THEN with the IF
              if (buffer[ptr] == TOKEN_THEN)
                ++ptr;

              // create subline from the condition (and THEN if it exists)
              sublines.add (new SubLine (this, startPtr, ptr - startPtr));
              startPtr = ptr;
            }
            break;

          // end of subline, so add it, advance startPtr and continue
          case ASCII_COLON:
            if (!inString && !inRemark)
            {
              sublines.add (new SubLine (this, startPtr, ptr - startPtr));
              startPtr = ptr;
            }
            break;

          case TOKEN_REM:
            if (!inString && !inRemark)
              inRemark = true;
            break;

          case ASCII_QUOTE:
            if (!inRemark)
              inString = !inString;
            break;
        }
      }

      // add whatever is left
      sublines.add (new SubLine (this, startPtr, ptr - startPtr));
      this.length = ptr - linePtr;
    }
  }

  private class SubLine
  {
    SourceLine parent;
    int startPtr;
    int length;
    String[] nextVariables;
    String forVariable = "";
    int targetLine = -1;

    // used for aligning the equals sign
    int assignEqualPos;

    public SubLine (SourceLine parent, int startPtr, int length)
    {
      this.parent = parent;
      this.startPtr = startPtr;
      this.length = length;

      byte b = buffer[startPtr];
      if ((b & 0x80) > 0) // token
      {
        switch (b)
        {
          case TOKEN_FOR:
            int p = startPtr + 1;
            while (buffer[p] != TOKEN_EQUALS)
              forVariable += (char) buffer[p++];
            break;

          case TOKEN_NEXT:
            if (length == 2) // no variables
              nextVariables = new String[0];
            else
            {
              String varList = new String (buffer, startPtr + 1, length - 2);
              nextVariables = varList.split (",");
            }
            break;

          case TOKEN_LET:
            recordEqualsPosition ();
            break;

          case TOKEN_GOTO:
            String target = new String (buffer, startPtr + 1, length - 2);
            try
            {
              gotoLines.add (Integer.parseInt (target));
            }
            catch (NumberFormatException e)
            {
              System.out.println ("Error parsing : GOTO " + target + " in "
                    + parent.lineNumber);
            }
            break;

          case TOKEN_GOSUB:
            String target2 = new String (buffer, startPtr + 1, length - 2);
            try
            {
              gosubLines.add (Integer.parseInt (target2));
            }
            catch (NumberFormatException e)
            {
              System.out.println ("Error parsing : GOSUB " + target2 + " in "
                    + parent.lineNumber);
            }
            break;
        }
      }
      else
      {
        if (b >= 48 && b <= 57)       // numeric, so must be a line number
        {
          String target = new String (buffer, startPtr, length - 1);
          try
          {
            targetLine = Integer.parseInt (target);
            gotoLines.add (targetLine);
          }
          catch (NumberFormatException e)
          {
            System.out.println (target);
            System.out.println (HexFormatter.format (buffer, startPtr, length - 1));
            System.out.println (e.toString ());
          }
        }
        else if (alignAssign)
          recordEqualsPosition ();
      }
    }

    private boolean isImpliedGoto ()
    {
      byte b = buffer[startPtr];
      if ((b & 0x80) > 0) // token
        return false;
      return (b >= 48 && b <= 57);
    }

    // Record the position of the equals sign so it can be aligned with adjacent lines.
    private void recordEqualsPosition ()
    {
      int p = startPtr + 1;
      int max = startPtr + length;
      while (buffer[p] != TOKEN_EQUALS && p < max)
        p++;
      if (buffer[p] == TOKEN_EQUALS)
        assignEqualPos = toString ().indexOf ('='); // use expanded line
    }

    private boolean isJoinableRem ()
    {
      return is (TOKEN_REM) && !isFirst ();
    }

    public boolean isFirst ()
    {
      return (parent.linePtr + 4) == startPtr;
    }

    public boolean is (byte token)
    {
      return buffer[startPtr] == token;
    }

    public boolean isEmpty ()
    {
      return length == 1 && buffer[startPtr] == 0;
    }

    public boolean containsToken ()
    {
      // ignore first byte, check the rest for tokens
      for (int p = startPtr + 1, max = startPtr + length; p < max; p++)
        if ((buffer[p] & 0x80) > 0)
          return true;
      return false;
    }

    public int getAddress ()
    {
      return getLoadAddress () + startPtr;
    }

    public String getAlignedText (int alignPosition)
    {
      StringBuilder line = toStringBuilder ();

      while (alignPosition-- > assignEqualPos)
        line.insert (assignEqualPos, ' ');

      return line.toString ();
    }

    // A REM statement might conceal an assembler routine
    public String[] getAssembler ()
    {
      byte[] buffer2 = new byte[length - 1];
      System.arraycopy (buffer, startPtr + 1, buffer2, 0, buffer2.length);
      AssemblerProgram program =
            new AssemblerProgram ("REM assembler", buffer2, getAddress () + 1);
      return program.getAssembler ().split ("\n");
    }

    @Override
    public String toString ()
    {
      return toStringBuilder ().toString ();
    }

    public StringBuilder toStringBuilder ()
    {
      StringBuilder line = new StringBuilder ();

      // All sublines end with 0 or : except IF lines that are split into two
      int max = startPtr + length - 1;
      if (buffer[max] == 0)
        --max;

      for (int p = startPtr; p <= max; p++)
      {
        byte b = buffer[p];
        if ((b & 0x80) > 0) // token
        {
          if (line.length () > 0 && line.charAt (line.length () - 1) != ' ')
            line.append (' ');
          int val = b & 0x7F;
          if (val < ApplesoftConstants.tokens.length)
            line.append (ApplesoftConstants.tokens[b & 0x7F]);
          // else
          // System.out.println ("Bad value : " + val + " " + line.toString () + " "
          // + parent.lineNumber);
        }
        else if (b < 32) // CTRL character
          line.append ("^" + (char) (b + 64)); // would be better in inverse text
        else
          line.append ((char) b);
      }

      return line;
    }
  }
}