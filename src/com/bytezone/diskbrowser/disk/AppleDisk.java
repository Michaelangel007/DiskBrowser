package com.bytezone.diskbrowser.disk;

import java.awt.AWTEventMulticaster;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import com.bytezone.diskbrowser.FileFormatException;
import com.bytezone.diskbrowser.HexFormatter;
import com.bytezone.diskbrowser.applefile.AppleFileSource;

public class AppleDisk implements Disk
{
  static final String newLine = String.format ("%n");
  static final int MAX_INTERLEAVE = 3;

  public final File path;
  private final byte[] diskBuffer;        // contains the disk contents in memory

  private final int tracks;               // usually 35 for floppy disks
  private int sectors;                    // 8 or 16
  private int blocks;                     // 280 or 560

  private final int trackSize;            // 4096
  public int sectorSize;                  // 256 or 512

  private int interleave = 0;
  private static int[][] interleaveSector = //
      { { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 },       // Dos
        { 0, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 15 },       // Prodos
        { 0, 13, 11, 9, 7, 5, 3, 1, 14, 12, 10, 8, 6, 4, 2, 15 },       // Infocom
        { 0, 6, 12, 3, 9, 15, 14, 5, 11, 2, 8, 7, 13, 4, 10, 1 } };     // CPM

  // Info from http://www.applelogic.org/TheAppleIIEGettingStarted.html
  // Block:    0 1 2 3 4 5 6 7 8 9 A B C D E F
  // Position: 0 8 1 9 2 A 3 B 4 C 5 D 6 E 7 F    - Prodos (.PO disks)
  // Position: 0 7 E 6 D 5 C 4 B 3 A 2 9 1 8 F    - Dos    (.DO disks)

  private boolean[] hasData;
  private ActionListener actionListenerList;
  private List<DiskAddress> blockList;

  public AppleDisk (File path, int tracks, int sectors) throws FileFormatException
  {
    assert (path.exists ()) : "No such path :" + path.getAbsolutePath ();
    assert (!path.isDirectory ()) : "File is directory :" + path.getAbsolutePath ();
    assert (path.length () <= Integer.MAX_VALUE) : "File too large";
    assert (path.length () != 0) : "File empty";

    int skip = 0;

    String name = path.getName ();
    int pos = name.lastIndexOf ('.');
    if (pos > 0 && name.substring (pos + 1).equalsIgnoreCase ("2mg"))
    {
      byte[] buffer = getPrefix (path);
      this.blocks = HexFormatter.intValue (buffer[20], buffer[21]);       // 1600
      this.sectorSize = 512;
      this.trackSize = 8 * sectorSize;
      tracks = this.blocks / 8;
      sectors = 8;
      skip = HexFormatter.intValue (buffer[8], buffer[9]);
    }
    else if (pos > 0 && name.substring (pos + 1).equalsIgnoreCase ("HDV"))
    {
      this.blocks = (int) path.length () / 4096 * 8; // reduces blocks to a legal multiple
      this.sectorSize = 512;
      this.trackSize = sectors * sectorSize;
    }
    else
    {
      this.blocks = tracks * sectors;
      this.sectorSize = (int) path.length () / blocks;
      this.trackSize = sectors * sectorSize;
    }

    if (false)
    {
      System.out.println ("Tracks      : " + tracks);
      System.out.println ("Sectors     : " + sectors);
      System.out.println ("Blocks      : " + blocks);
      System.out.println ("Sector size : " + sectorSize);
      System.out.println ("Track size  : " + trackSize);
      System.out.println ();
    }

    if (sectorSize != 256 && sectorSize != 512)
    {
      System.out.println ("Invalid sector size : " + sectorSize);
      new Exception ().printStackTrace ();
    }

    if (sectorSize != 256 && sectorSize != 512)
      throw new FileFormatException ("Invalid sector size : " + sectorSize);

    this.path = path;
    this.tracks = tracks;
    this.sectors = sectors;

    diskBuffer = new byte[tracks * sectors * sectorSize];
    hasData = new boolean[blocks];

    try
    {
      BufferedInputStream file = new BufferedInputStream (new FileInputStream (path));
      if (skip > 0)
        file.skip (skip);
      file.read (diskBuffer);
      file.close ();
    }
    catch (IOException e)
    {
      e.printStackTrace ();
      System.exit (1);
    }

    checkSectorsForData ();
  }

  private byte[] getPrefix (File path)
  {
    byte[] buffer = new byte[64];
    try
    {
      BufferedInputStream file = new BufferedInputStream (new FileInputStream (path));
      file.read (buffer);
      file.close ();
    }
    catch (IOException e)
    {
      e.printStackTrace ();
      System.exit (1);
    }
    return buffer;
  }

  private void checkSectorsForData ()
  {
    blockList = null; // force blockList to be rebuilt with the correct number/size of blocks
    for (DiskAddress da : this)
    {
      byte[] buffer = readSector (da);
      hasData[da.getBlock ()] = false;
      for (int i = 0; i < sectorSize; i++)
        if (buffer[i] != 0)
        {
          hasData[da.getBlock ()] = true;
          break;
        }
    }
  }

  /*
   * Routines that implement the Disk interface
   */

  @Override
  public int getSectorsPerTrack ()
  {
    return trackSize / sectorSize;
  }

  @Override
  public int getTrackSize ()
  {
    return trackSize;
  }

  @Override
  public int getBlockSize ()
  {
    return sectorSize;
  }

  @Override
  public int getTotalBlocks ()
  {
    return blocks;
  }

  @Override
  public int getTotalTracks ()
  {
    return tracks;
  }

  @Override
  public boolean isSectorEmpty (DiskAddress da)
  {
    return !hasData[da.getBlock ()];
  }

  @Override
  public boolean isSectorEmpty (int block)
  {
    return !hasData[block];
  }

  @Override
  public boolean isSectorEmpty (int track, int sector)
  {
    return !hasData[getDiskAddress (track, sector).getBlock ()];
  }

  @Override
  public File getFile ()
  {
    return path;
  }

  @Override
  public byte[] readSector (DiskAddress da)
  {
    byte[] buffer = new byte[sectorSize];
    readBuffer (da, buffer, 0);
    return buffer;
  }

  @Override
  public byte[] readSectors (List<DiskAddress> daList)
  {
    byte[] buffer = new byte[daList.size () * sectorSize];
    int bufferOffset = 0;
    for (DiskAddress da : daList)
    {
      if (da != null) // text files may have gaps
        readBuffer (da, buffer, bufferOffset);
      bufferOffset += sectorSize;
    }
    return buffer;
  }

  @Override
  public byte[] readSector (int track, int sector)
  {
    return readSector (getDiskAddress (track, sector));
  }

  @Override
  public byte[] readSector (int block)
  {
    return readSector (getDiskAddress (block));
  }

  @Override
  public int writeSector (DiskAddress da, byte[] buffer)
  {
    System.out.println ("Not yet implemented");
    return -1;
  }

  @Override
  public void setInterleave (int interleave)
  {
    assert (interleave >= 0 && interleave <= MAX_INTERLEAVE) : "Invalid interleave";
    this.interleave = interleave;
    checkSectorsForData ();
    if (actionListenerList != null)
      notifyListeners ("Interleave changed");
  }

  @Override
  public int getInterleave ()
  {
    return interleave;
  }

  @Override
  public void setBlockSize (int size)
  {
    assert (size == 256 || size == 512) : "Invalid sector size : " + size;
    if (sectorSize == size)
      return;
    sectorSize = size;
    sectors = trackSize / sectorSize;
    blocks = tracks * sectors;
    System.out.printf ("New blocks: %d%n", blocks);
    hasData = new boolean[blocks];
    checkSectorsForData ();
    if (actionListenerList != null)
      notifyListeners ("Sector size changed");
  }

  @Override
  public DiskAddress getDiskAddress (int block)
  {
    if (!isValidAddress (block))
    {
      System.out.println ("Invalid block : " + block);
      return null;
    }
    return new AppleDiskAddress (block, this);
  }

  @Override
  public List<DiskAddress> getDiskAddressList (int... blocks)
  {
    List<DiskAddress> addressList = new ArrayList<DiskAddress> ();

    for (int block : blocks)
    {
      assert (isValidAddress (block)) : "Invalid block : " + block;
      addressList.add (new AppleDiskAddress (block, this));
    }
    return addressList;
  }

  @Override
  public DiskAddress getDiskAddress (int track, int sector)
  {
    // should this return null for invalid addresses?
    assert (isValidAddress (track, sector)) : "Invalid address : " + track + ", "
        + sector;
    return new AppleDiskAddress (track, sector, this);
  }

  @Override
  public boolean isValidAddress (int block)
  {
    if (block < 0 || block >= this.blocks)
      return false;
    return true;
  }

  @Override
  public boolean isValidAddress (int track, int sector)
  {
    if (track < 0 || track >= this.tracks)
      return false;
    if (sector < 0 || sector >= this.sectors)
      return false;
    return true;
  }

  @Override
  public boolean isValidAddress (DiskAddress da)
  {
    return isValidAddress (da.getTrack (), da.getSector ());
  }

  /*
   * This is the only method that transfers data from the disk buffer to an output buffer.
   * It handles sectors of 256 or 512 bytes, and both linear and interleaved sectors.
   */
  private void readBuffer (DiskAddress da, byte[] buffer, int bufferOffset)
  {
    if (da.getDisk () != this)
    {
      System.out.println (da.getDisk ());
      System.out.println (this);
    }

    assert da.getDisk () == this : "Disk address not applicable to this disk";
    assert sectorSize == 256 || sectorSize == 512 : "Invalid sector size : " + sectorSize;
    assert interleave >= 0 && interleave <= MAX_INTERLEAVE : "Invalid interleave : "
        + interleave;
    int diskOffset;

    if (sectorSize == 256)
    {
      diskOffset = da.getTrack () * trackSize
          + interleaveSector[interleave][da.getSector ()] * sectorSize;
      System.arraycopy (diskBuffer, diskOffset, buffer, bufferOffset, sectorSize);
    }
    else if (sectorSize == 512)
    {
      diskOffset = da.getTrack () * trackSize
          + interleaveSector[interleave][da.getSector () * 2] * 256;
      System.arraycopy (diskBuffer, diskOffset, buffer, bufferOffset, 256);
      diskOffset = da.getTrack () * trackSize
          + interleaveSector[interleave][da.getSector () * 2 + 1] * 256;
      System.arraycopy (diskBuffer, diskOffset, buffer, bufferOffset + 256, 256);
    }
  }

  @Override
  public void addActionListener (ActionListener actionListener)
  {
    actionListenerList = AWTEventMulticaster.add (actionListenerList, actionListener);
  }

  @Override
  public void removeActionListener (ActionListener actionListener)
  {
    actionListenerList = AWTEventMulticaster.remove (actionListenerList, actionListener);
  }

  public void notifyListeners (String text)
  {
    if (actionListenerList != null)
      actionListenerList
          .actionPerformed (new ActionEvent (this, ActionEvent.ACTION_PERFORMED, text));
  }

  public AppleFileSource getDetails ()
  {
    return new DefaultAppleFileSource (toString (), path.getName (), null);
  }

  @Override
  public String toString ()
  {
    StringBuilder text = new StringBuilder ();

    text.append ("Path............ " + path.getAbsolutePath () + newLine);
    text.append (String.format ("File size....... %,d%n", path.length ()));
    text.append ("Tracks.......... " + tracks + newLine);
    text.append ("Sectors......... " + sectors + newLine);
    text.append (String.format ("Blocks.......... %,d%n", blocks));
    text.append ("Sector size..... " + sectorSize + newLine);
    text.append ("Interleave...... " + interleave + newLine);

    return text.toString ();
  }

  @Override
  public Iterator<DiskAddress> iterator ()
  {
    if (blockList == null)
    {
      blockList = new ArrayList<DiskAddress> (blocks);
      for (int block = 0; block < blocks; block++)
        blockList.add (new AppleDiskAddress (block, this));
    }
    return blockList.iterator ();
  }

  @Override
  public long getBootChecksum ()
  {
    byte[] buffer = readSector (0, 0);
    Checksum checksum = new CRC32 ();
    checksum.update (buffer, 0, buffer.length);
    return checksum.getValue ();
  }
}