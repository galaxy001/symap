package symap3D;

import java.applet.Applet;
import java.awt.GraphicsConfiguration;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;

import javax.swing.border.BevelBorder;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.media.j3d.Canvas3D;
import javax.media.j3d.RenderingErrorListener;
import javax.media.j3d.RenderingError;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.util.TreeSet;
import java.util.Vector;

import backend.Utils;
import circview.CircFrame;

import com.sun.j3d.utils.universe.SimpleUniverse;

import symap.SyMAP;
import symap.projectmanager.common.Project;
import symap.drawingpanel.DrawingPanel;
import symap.frame.SyMAPFrame;
import symap.frame.HelpBar;
import symap.frame.HelpListener;
import symap.projectmanager.common.Block;
import symap.projectmanager.common.CollapsiblePanel;
import symap.projectmanager.common.SyMAPFrameCommon;
import symap.projectmanager.common.TrackCom;
import util.DatabaseReader;
import util.ImageViewer;
import util.LinkLabel;
import util.Utilities;
import dotplot.DotPlotFrame;

@SuppressWarnings("serial") // Prevent compiler warning for missing serialVersionUID
public class SyMAPFrame3D extends SyMAPFrameCommon {
	private int VIEW_3D = 1;
	private int VIEW_2D = 2;
	private int VIEW_DP = 3;
	private int VIEW_CIRC = 4;
//	
	private MutexButtonPanel navControlBar;
	private MutexButtonPanel viewControlBar;
//	private JSlider sldRotate = null;
	private JButton btnShowCirc;
//	private JPanel controlPanel;
//	private JPanel cardPanel;
//	private JSplitPane splitPane;
//	
//	private HelpBar helpBar;
	private Canvas3D canvas3D;
	private SimpleUniverse universe;
//	private Mapper3D mapper;
//	private SyMAP symap2D = null;
//	private DotPlotFrame dotplot = null;
//	private Applet applet;
//	private DatabaseReader dbReader;
//	
//	private boolean hasInit = false;
//	private boolean isFirst2DView = true;
//	private boolean isFirstDotplotView = true;
//	private int selectedView = 1;
//	private int screenWidth, screenHeight;
//	
	private static GraphicsConfiguration preferredGraphicsConfig = SimpleUniverse.getPreferredConfiguration();
	
	public SyMAPFrame3D(Applet applet, DatabaseReader dbReader, Mapper3D mapper) {
		super(applet, dbReader);
		
		this.mapper = mapper;
		
		setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		
		// Override default rendering error listener to prevent exit on error.
/*		if (SyMAP3D.checkJava3DPreferred()) { // method not supported in Java3D 1.3.1 needed for Mac
			SimpleUniverse.addRenderingErrorListener(new RenderingErrorListener() {
	        	public void errorOccurred(RenderingError e) {
	        		e.printVerbose();
	        	}
	        });
		}	*/	
	}
	
	// mdb added 1/28/10
	public void dispose() { // override
		setVisible(false); // necessary?
		if (universe != null) universe.cleanup();
		universe = null;
		if (symap2D != null) symap2D.clear();
		symap2D = null;
		dotplot = null;
		applet = null;
		
		super.dispose();
	}
	
	private void create3DViewPanel() {
		if (sldRotate != null)
			helpBar.removeHelpListener(sldRotate);
		
        sldRotate = new JSlider(JSlider.HORIZONTAL, 0, 360, 0);
        sldRotate.addChangeListener(new ChangeListener() {
        	public void stateChanged(ChangeEvent e) {
        		JSlider source = (JSlider)e.getSource();
	        	int x = (int)source.getValue();   
	        	((Mapper3D)mapper).yRotateScene(x);
        	}
        });
        
        helpBar.addHelpListener(sldRotate,this);
        
        create3DNavigationControlBar();
        
    	canvas3D = new Canvas3D(preferredGraphicsConfig);
    	canvas3D.setMinimumSize(new Dimension(300, 300));
    	helpBar.addHelpListener(canvas3D,this);

        universe = new SimpleUniverse(canvas3D);
        universe.getViewingPlatform().setNominalViewingTransform();
        universe.addBranchGraph( ((Mapper3D)mapper).createSceneGraph(universe) );
        
        JPanel viewPanel3D = new JPanel();
        viewPanel3D.setLayout(new BorderLayout());
        viewPanel3D.setMinimumSize(new Dimension(200, 200));
        viewPanel3D.add(navControlBar, BorderLayout.NORTH);
        viewPanel3D.add(canvas3D, BorderLayout.CENTER);
        viewPanel3D.add(sldRotate, BorderLayout.SOUTH);
        
        cardPanel.add(viewPanel3D, Integer.toString(VIEW_3D));
        setView(VIEW_3D);
	}
	private void regenerateCircleView()
	{	
		int[] pidxList = new int[mapper.getProjects().length];
		TreeSet<Integer> shownGroups = new TreeSet<Integer>();
		for (int i = 0; i < mapper.getProjects().length; i++)
		{
			int pid = mapper.getProjects()[i].getID();
			pidxList[i] = pid;
			for (TrackCom t : (TrackCom[])((Mapper3D)mapper).getTracks(pid))
			{
				if (t.isVisible() || mapper.getReferenceTrack() == t)
				{
					shownGroups.add(t.getGroupIdx());
				}
			}
		}
		CircFrame circframe = new CircFrame(applet,dbReader,pidxList,shownGroups,helpBar);
		cardPanel.add(circframe.getContentPane(), Integer.toString(VIEW_CIRC)); // ok to add more than once
		setView(VIEW_CIRC);
	}	
	public void regenerate3DView() {
		((Mapper3D)mapper).savePosition();
		((Mapper3D)mapper).createSceneGraph(universe);
		((Mapper3D)mapper).restorePosition();

		setView(VIEW_3D);
	}
	
	public String getNavigationFunctionName() {
		switch (mapper.getNavigationFunction()) {
			case Mapper3D.NAVIGATION_ROTATE    : return "rotate";
			case Mapper3D.NAVIGATION_TRANSLATE : return "move";
			case Mapper3D.NAVIGATION_ZOOM      : return "zoom";
		}
		return "";
	}
	
	public String getHelpText(MouseEvent event) { // HelpListener interface
		Object o = event.getSource();
		
		if (o == sldRotate)
			return	"y-Rotate: Drag the slider forward and backward to " +
					"rotate the 3D view around the y-axis.";
		else if (o == canvas3D) {
			if (((Mapper3D)mapper).getPickFunction() == Mapper3D.PICK_DELETE)
				return "Click on a sequence in the 3D view to remove it.\n" +
					"Click the View 2D button to open a 2D view of the sequences " +
					"shown in the 3D view.";
			
			return	"Drag the mouse over the 3D view to " +
					getNavigationFunctionName() + " it.\n" +
					"Click the View 2D button to open a 2D view of the sequences " +
					"shown in the 3D view.";
		}
		else if (o instanceof ProjectPanel3D)
			return	"Click a chromosome to add/remove it from the scene.\n" +
					"Click the chromosome number to make it the reference.\n";
		
		return null;
	}
	
	private void create3DNavigationControlBar() {
		navControlBar = new MutexButtonPanel("Navigation: ");
		//navControlBar.setBorder(BorderFactory.createEtchedBorder());
		
		JButton b;
		b = navControlBar.addButton("Rotate", "/images/rotate.gif");
		b.addActionListener(
			new ActionListener() {
				public void actionPerformed(ActionEvent e) { 
					((Mapper3D)mapper).setNavigationFunction(Mapper3D.NAVIGATION_ROTATE);
					((Mapper3D)mapper).setPickFunction(Mapper3D.PICK_DO_NOTHING);
				}
		});
		helpBar.addHelpListener(b, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return 	"Rotate: Select this button to change the " +
						"mouse drag function to Rotate.  Then drag the " +
						"mouse over the 3D view to rotate it around the " + 
						"reference sequence.";
			}
		});
		
		b = navControlBar.addButton("Move", "/images/move.gif");
		b.addActionListener(
			new ActionListener() {
				public void actionPerformed(ActionEvent e) { 
					((Mapper3D)mapper).setNavigationFunction(Mapper3D.NAVIGATION_TRANSLATE);
					((Mapper3D)mapper).setPickFunction(Mapper3D.PICK_DO_NOTHING);
				}
		});
		helpBar.addHelpListener(b, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return 	"Move: Select this button to change the " +
						"mouse drag function to Move.  Then drag the " +
						"mouse over the 3D view to move it horizontally and vertically.";
			}
		});
		
		b = navControlBar.addButton("Zoom", "/images/zoom.gif");
		b.addActionListener(
			new ActionListener() {
				public void actionPerformed(ActionEvent e) { 
					((Mapper3D)mapper).setNavigationFunction(Mapper3D.NAVIGATION_ZOOM);
					((Mapper3D)mapper).setPickFunction(Mapper3D.PICK_DO_NOTHING);
				}
		});
		helpBar.addHelpListener(b, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return 	"Zoom: Select this button to change the " +
						"mouse drag function to Zoom.  Then drag the " +
						"mouse over the 3D view to Zoom it forward and background.";
			}
		});
		
		JButton helpButton = new JButton();
		helpButton.setToolTipText("Online documentation");
		helpButton.setIcon( ImageViewer.getImageIcon("/images/help.gif") );
		helpButton.setMargin(new Insets(4, 3, 4, 3));
		helpButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				String url = SyMAP.USER_GUIDE_URL + "#alignment_display_3d";
				if ( !Utilities.tryOpenURL(applet, url) )
					System.err.println("Error opening URL: " + url);
			}
		});
		helpBar.addHelpListener(helpButton, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return 	"Help: Click to open the online manual in a web browser." + Utilities.getBrowserPopupMessage(applet);
			}
		});
		
		JPanel helpButtonPanel = new JPanel();
	    helpButtonPanel.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 0));
		helpButtonPanel.add( helpButton );
		navControlBar.add(helpButtonPanel);
	}
	
	private void setView(int viewNum) {
		if (viewNum == VIEW_3D) {
			((CardLayout)cardPanel.getLayout()).show(cardPanel, Integer.toString(VIEW_3D));
		}
		else if (viewNum == VIEW_2D) {
			((CardLayout)cardPanel.getLayout()).show(cardPanel, Integer.toString(VIEW_2D));
		}
		else if (viewNum == VIEW_DP) {
			((CardLayout)cardPanel.getLayout()).show(cardPanel, Integer.toString(VIEW_DP));
		}
		else if (viewNum == VIEW_CIRC) {
			((CardLayout)cardPanel.getLayout()).show(cardPanel, Integer.toString(VIEW_CIRC));
		}
	}
	
	private JPanel createViewControlBar() {
		viewControlBar = new MutexButtonPanel("Views:", 5);
		viewControlBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		viewControlBar.setBorder(BorderFactory.createCompoundBorder(
				//BorderFactory.createLineBorder(Color.LIGHT_GRAY),
				BorderFactory.createEtchedBorder(),
				BorderFactory.createEmptyBorder(5, 5, 5, 5)));
		
		JButton b;
		b = viewControlBar.addButton("3D", null, new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				Utilities.setCursorBusy(getContentPane(), true);
				regenerate3DView();
				selectedView = viewControlBar.getSelected();
				Utilities.setCursorBusy(getContentPane(), false);
			}
		});
		helpBar.addHelpListener(b, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return 	"Show 3D View: Click this buttons to switch to the 3D view.";
			}
		});
		
		btnShow2D = new JButton("2D");
		btnShow2D.setEnabled(false);
		btnShow2D.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				Utilities.setCursorBusy(getContentPane(), true);
				boolean success = regenerate2DView();
				if (success)
					selectedView = viewControlBar.getSelected();
				else { // revert to previous view
					viewControlBar.setSelected(selectedView);
					viewControlBar.getSelectedButton().setEnabled(true);
				}
				Utilities.setCursorBusy(getContentPane(), false);
			}
		});
		viewControlBar.addButton(btnShow2D);
		helpBar.addHelpListener(btnShow2D, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return "Show 2D View: Click this button to switch to the 2D view.";
			}
		});
		
		btnShowDotplot = new JButton("Dotplot");
		btnShowDotplot.setEnabled(false);
		btnShowDotplot.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				Utilities.setCursorBusy(getContentPane(), true);
				regenerateDotplotView();
				selectedView = viewControlBar.getSelected();
				Utilities.setCursorBusy(getContentPane(), false);
			}
		});
		viewControlBar.addButton(btnShowDotplot);
		helpBar.addHelpListener(btnShowDotplot, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return "Show Dotplot View:  Click this button to switch to the Dotplot view.";
			}
		});

		btnShowCirc = new JButton("Circle");
		btnShowCirc.setEnabled(false);
		btnShowCirc.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				Utilities.setCursorBusy(getContentPane(), true);
				regenerateCircleView();
				selectedView = viewControlBar.getSelected();
				Utilities.setCursorBusy(getContentPane(), false);
			}
		});
		viewControlBar.addButton(btnShowCirc);
		helpBar.addHelpListener(btnShowCirc, new HelpListener() {
			public String getHelpText(MouseEvent e) { 
				return "Show Circle View:  Click this button to switch to the Circle view.";
			}
		});
		return viewControlBar;
	}
	
	private ActionListener projectChange = new ActionListener() {
		public void actionPerformed(ActionEvent e) {
			if (mapper.hasChanged()) {
				// Repaint project panels
				controlPanel.repaint();
				
				// Regenerate main display
				isFirst2DView = true;
				isFirstDotplotView = true;
				btnShow2D.setEnabled( mapper.getNumVisibleTracks() > 0 );
				btnShowDotplot.setEnabled( mapper.getNumVisibleTracks() > 0 );
				btnShowCirc.setEnabled( mapper.getNumVisibleTracks() > 0 );
				if (viewControlBar.getSelected() == VIEW_3D ) 
				{
					regenerate3DView();
				}
				else if (viewControlBar.getSelected() == VIEW_CIRC ) 
				{
					regenerateCircleView();
				}
			}
		}
	};
	
	private void createControlPanel() {
		// Create project graphical menus
        JPanel projPanel = new JPanel();
        projPanel.setLayout(new BoxLayout(projPanel, BoxLayout.Y_AXIS));
        projPanel.setMinimumSize(new Dimension(200, 200));
        projPanel.setVisible(true);
        splitPane.setLeftComponent(projPanel);
       
        //int maxHeight = 0;
		Project[] projects = mapper.getProjects();
		
		// First figure out which groups have synteny in this set
		TreeSet<Integer> grpIdxWithSynteny = new TreeSet<Integer>();
		for (Project p : projects) {
			Block[] blks = mapper.getBlocksForProject(p.getID());
			for (Block blk : blks)
			{
				grpIdxWithSynteny.add(blk.getGroup1Idx());
				grpIdxWithSynteny.add(blk.getGroup2Idx());
			}
		}
		
		for (Project p : projects) {
			ProjectPanel3D pd = new ProjectPanel3D((Mapper3D) mapper, p, projectChange, grpIdxWithSynteny);
			helpBar.addHelpListener(pd,this);
			
			CollapsiblePanel cp = new CollapsiblePanel(p.getDisplayName(), null, false);
			cp.add(pd);
			cp.expand();
			//maxHeight += cp.getMaximumSize().getHeight() + 15;
			cp.setVisible(true);
			projPanel.add(cp);
		}
		
//		JScrollPane scroller = new JScrollPane( projPanel, 
//				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, 
//				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
//		scroller.setBorder(null);
//		scroller.getVerticalScrollBar().setUnitIncrement(5);
//		scroller.setMaximumSize(new Dimension(500, maxHeight));
//		scroller.setAlignmentX(Component.LEFT_ALIGNMENT);

		// Create instructions panel
        LinkLabel helpLink = new LinkLabel("Click for online help");
        helpLink.setFont(new Font(helpLink.getFont().getName(), Font.PLAIN, helpLink.getFont().getSize()));
        helpLink.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				Utilities.setCursorBusy(SyMAPFrame3D.this, true);
				if ( !Utilities.tryOpenURL(applet,SyMAP.USER_GUIDE_URL) )
					System.err.println("Error opening URL: " + SyMAP.USER_GUIDE_URL);
				Utilities.setCursorBusy(SyMAPFrame3D.this, false);
			}
		});
        
		CollapsiblePanel helpCollapsiblePanel = new CollapsiblePanel("Instructions", null, false);
		helpCollapsiblePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
		helpCollapsiblePanel.add( helpBar );
		helpCollapsiblePanel.add( helpLink );
		helpCollapsiblePanel.expand();
		
		// Setup top-level panel
        controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBorder( BorderFactory.createEmptyBorder(0, 5, 0, 0) );
		controlPanel.add( projPanel );
		controlPanel.add( helpCollapsiblePanel );
		controlPanel.add( Box.createVerticalStrut(20) );
		controlPanel.add( createViewControlBar() );
		controlPanel.add( Box.createVerticalStrut(10) );
		controlPanel.add(createDownloadBar());
		controlPanel.add( Box.createVerticalStrut(10) );
		controlPanel.add( Box.createVerticalGlue() );
		controlPanel.setVisible(true);
		splitPane.setLeftComponent(controlPanel);
		
	}
	
	public void build() { // can't be named "show()" because of override
		if (mapper == null)
			return;
		
		// Initialization is done here and not in constructor because the 
		// necessary data (tracks/blocks) aren't avail until now.
		if (!hasInit) {
			createControlPanel();
			create3DViewPanel();
		}
		
		if (mapper.getNumTracks() == 0) {
			System.err.println("No tracks to display!");
			return;
		}
		
		//setVisible(true); // mdb removed 12/31/09 #208
	}
	
	private void regenerateDotplotView() {
		// Get selected projects/groups
		int[] projects = mapper.getVisibleProjectIDs();
		int[] groups = mapper.getVisibleGroupIDs();
		
		// Split into x and y groups
		int[] xGroups = new int[] { groups[0] };
		int[] yGroups = new int[groups.length-1];
		for (int i = 1;  i < groups.length;  i++)
			yGroups[i-1] = groups[i];
		
		// Create dotplot frame
		if (dotplot == null)
			dotplot = new DotPlotFrame(applet, dbReader, projects, xGroups, yGroups, helpBar, false);
		else if (isFirstDotplotView)
			dotplot.getData().initialize(projects, xGroups, yGroups);

		// Switch to dotplot display
		cardPanel.add(dotplot.getContentPane(), Integer.toString(VIEW_DP)); // ok to add more than once
		setView(VIEW_DP);
		
		isFirstDotplotView = false;
	}
		
	private boolean regenerate2DView() {
		try {
			if (symap2D == null)
				symap2D = new SyMAP(applet, dbReader, helpBar, null);
			
			SyMAPFrame frame = symap2D.getFrame();
			if (frame == null) {
				System.err.println("SyMAPFrame3D:  Error creating 2D frame!");
				return false;
			}
			
			DrawingPanel dp = symap2D.getDrawingPanel();
			
			// WMN start the 2D over when they go back and forth
			if (true || isFirst2DView) { 
				// Get selected tracks
				TrackCom ref = ((Mapper3D)mapper).getReferenceTrack();
				TrackCom[] selectedTracks = ((Mapper3D)mapper).getVisibleTracks();
				
				if (selectedTracks.length > 4 &&
						JOptionPane.showConfirmDialog(null,"This view may take a while to load and/or cause SyMAP to run out of memory, try anyway?","Warning",
							JOptionPane.YES_NO_OPTION,JOptionPane.ERROR_MESSAGE) != JOptionPane.YES_OPTION)
				{
					return false;
				}
				
				// Disable 2D rendering
				dp.setFrameEnabled(false);
				
				// Clear existing
				dp.resetData(); // clear caches
				symap2D.getHistory().clear(); // clear history
				dp.setMaps(0);
				
				// Setup 2D
				int position = 1;
				for (int i = 0;  i < selectedTracks.length;  i++) {
					TrackCom t = selectedTracks[i];
					
					//System.out.println("position " + position + " " + t.getFullName());
					
					// Add track
					if (t.isPseudo())
						dp.setSequenceTrack( position++, t.getProjIdx(), t.getGroupIdx(), t.getColor() );
					else {
						String contigs = Mapper3D.getContigListForBlocks(
								mapper.getBlocksForTracks( t.getGroupIdx(), ref.getGroupIdx() ));
						//System.out.println("contigs: "+contigs);
						dp.setBlockTrack( position++, t.getProjIdx(), "Chr " + t.getGroupName(), contigs, t.getColor() );
					}
					
					// Add alternating reference track
					if (selectedTracks.length == 1 || selectedTracks.length-1 != i) { // middle tracks
						if (ref.isPseudo())
							dp.setSequenceTrack( position++, ref.getProjIdx(), ref.getGroupIdx(), ref.getColor() );
						else {
							// Get contigs for left track
							String contigs = Mapper3D.getContigListForBlocks(
									mapper.getBlocksForTracks( ref.getGroupIdx(), t.getGroupIdx() ));
							// Get contigs for right track if present
							if (i+1 < selectedTracks.length)
								contigs += Mapper3D.getContigListForBlocks(
										mapper.getBlocksForTracks( ref.getGroupIdx(), selectedTracks[i+1].getGroupIdx() ));
							//System.out.println("contigs: "+contigs);
							dp.setBlockTrack( position++, ref.getProjIdx(), "Chr " + ref.getGroupName(), contigs, ref.getColor() );
						}
					}
				}
				dp.setMaps( position - 2 );
			}
			
			// Enable 2D display
			cardPanel.add(frame.getContentPane(), Integer.toString(VIEW_2D)); // ok to add more than once
			setView(VIEW_2D);
			
			dp.amake(); // redraw and make visible
			
			isFirst2DView = false;
			return true;
		}
		catch (OutOfMemoryError e) { // mdb added 1/29/10
			symap2D = null;
			Utilities.showOutOfMemoryMessage();
		}
		catch (Exception err) {
			err.printStackTrace();
		}
		
		return false;
	}
	
	private class MutexButtonPanel extends JPanel implements ActionListener {
		private GridBagConstraints constraints;
		
		public MutexButtonPanel() {
			this(null, 0);
		}
		
		public MutexButtonPanel(String title) {
			this(title, 0);
		}
		
		public MutexButtonPanel(String title, int pad) {
			GridBagLayout layout = new GridBagLayout();
			setLayout(layout);
			
			constraints = new GridBagConstraints();
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.gridheight = 1;
			constraints.ipadx = 5;
			constraints.ipady = 8;
			if (pad > 0) constraints.insets = new Insets(0, 0, 0, pad);
			
			JLabel label = new JLabel(title);
			label.setFont(label.getFont().deriveFont(Font.PLAIN));
			((GridBagLayout)getLayout()).setConstraints(label, constraints);
			add(label);
			label.setMinimumSize(label.getPreferredSize());
		}
		
		public JButton addButton(String strText, String strIconPath) {
			return addButton(strText, strIconPath, null);
		}
		
		public JButton addButton(String strText, String strIconPath, ActionListener l) {
			JButton button = new JButton();
			button.setToolTipText(strText);
			if (strIconPath != null)
				button.setIcon( ImageViewer.getImageIcon(strIconPath) );
			else
				button.setText(strText);
			
			if (l != null) button.addActionListener(l);
			addButton(button);
			
			return button;
		}
		
		public void addButton(JButton button) {
			button.setMargin(new Insets(0, 0, 0, 0));
			button.setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED));
			button.addActionListener(this);
			if (getComponentCount() == 1) // select first button
				setSelected(button);//button.doClick();
			
			((GridBagLayout)getLayout()).setConstraints(button, constraints);
			add(button);
			
			setMaximumSize(getPreferredSize());
		}
		
		public JButton getSelectedButton() {
			Component[] comps = getComponents();
			for (int i = 0;  i < comps.length;  i++) {
				if (comps[i] instanceof JButton) {
					JButton b = (JButton)comps[i];
					if (b.isSelected())
						return b;
				}
			}
			return null;
		}
		
		public int getSelected() {
			Component[] comps = getComponents();
			for (int i = 0;  i < comps.length;  i++) {
				if (comps[i] instanceof JButton) {
					JButton b = (JButton)comps[i];
					if (b.isSelected())
						return i;
				}
			}
			return -1;
		}
		
		public void setSelected(int n) {
			Component c = getComponent(n);
			if (c != null && c instanceof JButton)
				setSelected((JButton)c);
		}
		
		public void setSelected(JButton button) {
			// De-select all buttons
			for (Component c : getComponents()) {
				if (c instanceof JButton) {
					JButton b = (JButton)c;
					b.setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED));
					b.setSelected(false);
					b.setEnabled(true);
				}
			}
			
			// kludge:
			if (btnShow2D != null) btnShow2D.setEnabled( mapper.getNumVisibleTracks() > 0 );
			if (btnShowDotplot != null) btnShowDotplot.setEnabled( mapper.getNumVisibleTracks() > 0 );
			
			// Select this button
			button.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
			button.setSelected(true);
			button.setEnabled(false);
		}
		
		public void actionPerformed(ActionEvent e) {
			if (e.getSource() != null && e.getSource() instanceof JButton)
				setSelected((JButton)e.getSource());
		}
	}
	
	//
	// These two methods duplicated from SyMAPFrameCommon!!
	//
	private JPanel createDownloadBar() {
		JPanel pnl = new JPanel();
		pnl.setLayout( new BoxLayout(pnl, BoxLayout.LINE_AXIS) );
		pnl.setAlignmentX(LEFT_ALIGNMENT);
		pnl.setBackground(Color.white);
		JButton btn = new JButton("Download Blocks");
		btn.setBackground(Color.white);
		btn.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				downloadBlocks();
			}
		});		
		pnl.add(btn);
		System.err.println("Added");
		return pnl;
	}	
	   private void downloadBlocks() {
	    	try {
				JFileChooser chooser = new JFileChooser(System.getProperty("user.dir"));
				chooser.setSelectedFile(new File("blocks.tsv"));
				
				if(chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
					if(chooser.getSelectedFile() != null) {
						File f = chooser.getSelectedFile();
						if (f.exists()) {
							if (JOptionPane.YES_OPTION != JOptionPane.showConfirmDialog(null,"The file exists, do you want to overwrite it?", 
									"File exists",JOptionPane.YES_NO_OPTION))
							{
								return;
							}
							f.delete();
						}
						f.createNewFile();
						PrintWriter out = new PrintWriter(new FileWriter(chooser.getSelectedFile()));
						Vector<String> row = new Vector<String>();
						row.add("Species1");
						row.add("Species2");
						row.add("Chr1");
						row.add("Chr2");
						row.add("BlkNum");
						row.add("Start1");
						row.add("End1");
						row.add("Start2");
						row.add("End2");
						row.add("#Hits");
						row.add("Genes1");
						row.add("%Genes1");
						row.add("Genes2");
						row.add("%Genes2");
						row.add("PearsonR");
						out.println(Utils.join(row, "\t"));
						
						Vector<String> projList = new Vector<String>();
						Project[] projects = mapper.getProjects();
						for (Project p : projects) {
							projList.add(String.valueOf(p.getID()));
						}
						String projStr = Utils.join(projList,",");
						String query = "select p1.name, p2.name, g1.name, g2.name, b.blocknum, b.start1, b.end1," +
						" b.start2,b.end2,b.score,b.ngene1,b.genef1,b.ngene2,b.genef2,b.corr " +
						" from blocks as b join groups as g1 on g1.idx=b.grp1_idx join groups as g2 on g2.idx=b.grp2_idx " +
						" join projects as p1 on p1.idx=b.proj1_idx join projects as p2 on p2.idx=b.proj2_idx " +
						" where p1.idx in (" + projStr + ") and p2.idx in (" + projStr + ") and p1.type='pseudo' and p2.type='pseudo' " +
						" order by p1.name asc, p2.name asc, g1.idx asc, g2.idx asc, b.blocknum asc";
						//System.err.println(query);
						ResultSet rs = dbReader.getConnection().createStatement().executeQuery(query);
						int n = 0;
						while (rs.next())
						{
							for(int i = 1; i <= row.size(); i++)
							{
								row.set(i-1, rs.getString(i));
							}
							out.println(Utils.join(row, "\t"));
							n++;
						}
						out.close();
						System.err.println("Wrote " + n + " blocks");
					}
					

				}
			} catch(Exception e) {
				e.printStackTrace();
			}

	    }
	private class MySplitPane extends JSplitPane {
		public MySplitPane(int newOrientation) {
			super(newOrientation);
		}
		
		public void setDividerVisible(boolean visible) {
			((BasicSplitPaneUI)getUI()).getDivider().setVisible(visible);
		}
	}
}
