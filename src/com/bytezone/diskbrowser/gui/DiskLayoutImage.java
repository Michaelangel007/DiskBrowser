package com.bytezone.diskbrowser.gui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import com.bytezone.diskbrowser.disk.Disk;
import com.bytezone.diskbrowser.disk.DiskAddress;
import com.bytezone.diskbrowser.disk.FormattedDisk;
import com.bytezone.diskbrowser.disk.SectorType;
import com.bytezone.diskbrowser.gui.DiskLayoutPanel.LayoutDetails;
import com.bytezone.diskbrowser.gui.RedoHandler.RedoEvent;

class DiskLayoutImage extends JPanel implements Scrollable
{
  static final Cursor crosshairCursor = new Cursor (Cursor.CROSSHAIR_CURSOR);
  FormattedDisk disk;
  LayoutDetails layoutDetails;
  private boolean showFreeSectors;
  DiskLayoutSelection selectionHandler = new DiskLayoutSelection ();
  boolean redo;

  // set defaults (used until a real disk is set)
  int bw = 30;
  int bh = 15;
  int gw = 8;
  int gh = 35;

  public DiskLayoutImage ()
  {
    setPreferredSize (new Dimension (240 + 1, 525 + 1));
    addMouseListener (new MyMouseListener ());
    setBackground (Color.WHITE);
    setOpaque (true);
  }

  public void setDisk (FormattedDisk disk, LayoutDetails details)
  {
    this.disk = disk;
    layoutDetails = details;

    //    System.out.println (details);
    //    new Exception ().printStackTrace ();

    bw = layoutDetails.block.width;
    bh = layoutDetails.block.height;
    gw = layoutDetails.grid.width;
    gh = layoutDetails.grid.height;

    setPreferredSize (new Dimension (gw * bw + 1, gh * bh + 1));
    selectionHandler.setSelection (null);

    repaint ();
  }

  public void setShowFreeSectors (boolean showFree)
  {
    showFreeSectors = showFree;
    repaint ();
  }

  void setSelection (List<DiskAddress> sectors)
  {
    selectionHandler.setSelection (sectors);
    if (sectors != null && sectors.size () > 0)
    {
      DiskAddress da = sectors.size () == 1 ? sectors.get (0) : sectors.get (1);
      scrollRectToVisible (layoutDetails.getLocation (da));
    }
    repaint ();
  }

  @Override
  protected void paintComponent (Graphics g)
  {
    super.paintComponent (g);

    // why doesn't linux do this?
    //    g.setColor (Color.WHITE);
    //    g.fillRect (0, 0, getWidth (), getHeight ());

    if (disk == null)
      return;

    Rectangle clipRect = g.getClipBounds ();

    Point p1 = new Point (clipRect.x / bw * bw, clipRect.y / bh * bh);
    Point p2 =
        new Point ((clipRect.x + clipRect.width - 1) / bw * bw, (clipRect.y
            + clipRect.height - 1)
            / bh * bh);

    //    System.out.printf ("gw=%d, gh=%d, bw=%d, bh=%d%n", gw, gh, bw, bh);
    // int totalBlocks = 0;
    int maxBlock = gw * gh;
    //    System.out.printf ("Max blocks: %d%n", maxBlock);
    Disk d = disk.getDisk ();
    List<DiskAddress> selectedBlocks = selectionHandler.getHighlights ();

    // this stops an index error when using alt-5 to switch to 512-byte blocks
    if (maxBlock > d.getTotalBlocks ())
      maxBlock = d.getTotalBlocks ();

    for (int y = p1.y; y <= p2.y; y += bh)
      for (int x = p1.x; x <= p2.x; x += bw)
      {
        int blockNo = y / bh * gw + x / bw;
        if (blockNo < maxBlock)
        {
          DiskAddress da = d.getDiskAddress (blockNo);
          boolean flag = showFreeSectors && disk.isSectorFree (da);
          boolean selected = selectedBlocks.contains (da);
          drawBlock ((Graphics2D) g, blockNo, x, y, flag, selected);
          // totalBlocks++;
        }
      }
  }

  private void drawBlock (Graphics2D g, int blockNo, int x, int y, boolean flagFree,
      boolean selected)
  {
    SectorType type = disk.getSectorType (blockNo);
    int offset = (bw - 4) / 2 + 1;

    Rectangle rect = new Rectangle (x, y, bw, bh);
    //    System.out.printf ("Rect: %4d %4d %4d %4d%n", x, y, bw, bh);

    // draw frame
    if (true) // this needs to draw the outside rectangle, and show less white space
    // between blocks
    {
      g.setColor (Color.GRAY);
      g.drawRect (rect.x, rect.y, rect.width, rect.height);
    }

    // draw coloured block
    if (type.colour != Color.WHITE)
    {
      g.setColor (type.colour);
      // this is weird, the retina OSX screen needs the second fillRect
      // see also DiskLegendPanel.paint()
      if (false)
        g.fillRect (rect.x + 2, rect.y + 2, rect.width - 3, rect.height - 3);
      else
        g.fillRect (rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2);
    }

    // draw an indicator in free blocks
    if (flagFree)
    {
      g.setColor (getContrastColor (type));
      g.drawOval (rect.x + offset - 2, rect.y + 4, 7, 7);
    }

    // draw an indicator in selected blocks
    if (selected)
    {
      g.setColor (getContrastColor (type));
      g.fillOval (rect.x + offset, rect.y + 6, 3, 3);
    }
  }

  private Color getContrastColor (SectorType type)
  {
    if (type.colour == Color.WHITE || type.colour == Color.YELLOW
        || type.colour == Color.PINK || type.colour == Color.CYAN
        || type.colour == Color.ORANGE)
      return Color.BLACK;
    return Color.WHITE;
  }

  @Override
  public Dimension getPreferredScrollableViewportSize ()
  {
    return new Dimension (240 + 1, 525 + 1); // floppy disk size
  }

  @Override
  public int getScrollableUnitIncrement (Rectangle visibleRect, int orientation,
      int direction)
  {
    return orientation == SwingConstants.HORIZONTAL ? bw : bh;
  }

  @Override
  public int getScrollableBlockIncrement (Rectangle visibleRect, int orientation,
      int direction)
  {
    return orientation == SwingConstants.HORIZONTAL ? bw * 4 : bh * 10;
  }

  @Override
  public boolean getScrollableTracksViewportHeight ()
  {
    return false;
  }

  @Override
  public boolean getScrollableTracksViewportWidth ()
  {
    return false;
  }

  void redoEvent (RedoEvent redoEvent)
  {
    redo = true;
    SectorSelectedEvent event = (SectorSelectedEvent) redoEvent.value;
    setSelection (event.getSectors ());
    fireSectorSelectionEvent (event);
    redo = false;
  }

  private void fireSectorSelectionEvent ()
  {
    SectorSelectedEvent event =
        new SectorSelectedEvent (this, selectionHandler.getHighlights (), disk);
    fireSectorSelectionEvent (event);
  }

  private void fireSectorSelectionEvent (SectorSelectedEvent event)
  {
    event.redo = redo;
    SectorSelectionListener[] listeners =
        (listenerList.getListeners (SectorSelectionListener.class));
    for (SectorSelectionListener listener : listeners)
      listener.sectorSelected (event);
  }

  public void addSectorSelectionListener (SectorSelectionListener listener)
  {
    listenerList.add (SectorSelectionListener.class, listener);
  }

  public void removeSectorSelectionListener (SectorSelectionListener listener)
  {
    listenerList.remove (SectorSelectionListener.class, listener);
  }

  class MyMouseListener extends MouseAdapter
  {
    private Cursor currentCursor;

    @Override
    public void mouseClicked (MouseEvent e)
    {
      int x = e.getX () / bw;
      int y = e.getY () / bh;
      int blockNo = y * gw + x;
      DiskAddress da = disk.getDisk ().getDiskAddress (blockNo);

      boolean extend = ((e.getModifiersEx () & InputEvent.SHIFT_DOWN_MASK) > 0);
      boolean append = ((e.getModifiersEx () & InputEvent.CTRL_DOWN_MASK) > 0);

      selectionHandler.doClick (disk.getDisk (), da, extend, append);
      fireSectorSelectionEvent ();
      repaint ();
    }

    @Override
    public void mouseEntered (MouseEvent e)
    {
      currentCursor = getCursor ();
      setCursor (crosshairCursor);
    }

    @Override
    public void mouseExited (MouseEvent e)
    {
      setCursor (currentCursor);
    }
  }
}