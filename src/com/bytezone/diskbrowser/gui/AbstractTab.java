package com.bytezone.diskbrowser.gui;

/***********************************************************************************************
 * Parent class of FileSystemTab and AppleDiskTab.
 * 
 * 
 ***********************************************************************************************/

import static javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER;
import static javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.*;

import com.bytezone.diskbrowser.gui.RedoHandler.RedoData;
import com.bytezone.diskbrowser.gui.TreeBuilder.FileNode;

abstract class AbstractTab extends JPanel implements Tab
{
  private final static Cursor handCursor = new Cursor (Cursor.HAND_CURSOR);
  private final List<MouseAdapter> adapters = new ArrayList<MouseAdapter> ();
  private Font font;
  private final JScrollPane scrollpane;
  final DiskAndFileSelector eventHandler;
  final RedoHandler navMan;
  final RedoData redoData;
  protected JTree tree;

  public AbstractTab (RedoHandler navMan, DiskAndFileSelector selector, Font font)
  {
    super (new BorderLayout ());
    this.eventHandler = selector;
    this.font = font;
    this.navMan = navMan;
    this.redoData = navMan.createData ();

    scrollpane = new JScrollPane (null, VERTICAL_SCROLLBAR_ALWAYS, HORIZONTAL_SCROLLBAR_NEVER);
    scrollpane.setBorder (null);
    add (scrollpane, BorderLayout.CENTER);
  }

  protected void setTree (JTree tree)
  {
    this.tree = tree;
    tree.setFont (font);
    scrollpane.setViewportView (tree);
    TreeSelectionModel tsm = tree.getSelectionModel ();
    tsm.setSelectionMode (TreeSelectionModel.SINGLE_TREE_SELECTION);

    if (adapters.size () > 0)
      restoreAdapters ();
    else
      addTreeMouseListener (new CursorHandler ());
  }

  protected void setTreeFont (Font font)
  {
    tree.setFont (font);
    this.font = font;
  }

  public void addTreeMouseListener (MouseAdapter adapter)
  {
    tree.addMouseListener (adapter);
    adapters.add (adapter);
  }

  private void restoreAdapters ()
  {
    for (MouseAdapter ma : adapters)
      tree.addMouseListener (ma);
  }

  protected Object getSelectedObject ()
  {
    DefaultMutableTreeNode node =
          (DefaultMutableTreeNode) tree.getLastSelectedPathComponent ();
    return node == null ? null : node.getUserObject ();
  }

  @Override
  public DefaultMutableTreeNode getRootNode ()
  {
    return (DefaultMutableTreeNode) tree.getModel ().getRoot ();
  }

  protected DefaultMutableTreeNode findNode (int nodeNo)
  {
    DefaultMutableTreeNode rootNode = getRootNode ();
    Enumeration<DefaultMutableTreeNode> children = rootNode.breadthFirstEnumeration ();
    int count = 0;
    DefaultMutableTreeNode selectNode = null;
    while (children.hasMoreElements () && ++count <= nodeNo)
      selectNode = children.nextElement ();
    return selectNode;
  }

  protected DefaultMutableTreeNode findFirstLeafNode ()
  {
    DefaultMutableTreeNode rootNode = getRootNode ();
    Enumeration<DefaultMutableTreeNode> children = rootNode.depthFirstEnumeration ();
    DefaultMutableTreeNode selectNode = null;
    while (children.hasMoreElements ())
    {
      selectNode = children.nextElement ();
      if (selectNode.isLeaf ())
      {
        FileNode node = (FileNode) selectNode.getUserObject ();
        if (node.file.isFile ())
          return selectNode;
      }
    }
    return null;
  }

  // Trigger the TreeSelectionListener set by the real Tab (if the value is different)
  protected void showNode (DefaultMutableTreeNode showNode)
  {
    TreePath tp = getPathToNode (showNode);
    tree.setSelectionPath (tp);
    if (!tree.isVisible (tp))
      tree.scrollPathToVisible (tp);
  }

  protected TreePath getPathToNode (DefaultMutableTreeNode selectNode)
  {
    DefaultTreeModel treeModel = (DefaultTreeModel) tree.getModel ();
    TreeNode[] nodes = treeModel.getPathToRoot (selectNode);
    return new TreePath (nodes);
  }

  private class CursorHandler extends MouseAdapter
  {
    private Cursor oldCursor;

    @Override
    public void mouseEntered (MouseEvent e)
    {
      oldCursor = getCursor ();
      setCursor (handCursor);
    }

    @Override
    public void mouseExited (MouseEvent e)
    {
      setCursor (oldCursor);
    }
  }
}