package backend;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Enumeration;
import java.util.Properties;
import java.util.TreeMap;
import java.util.NavigableMap;
import java.util.Vector;
import java.util.Comparator;
import java.util.Collections;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import backend.Block; // mdb added 3/16/09 - redundant class names
import symap.pool.DatabaseUser;
import util.ErrorCount;
import util.Logger;
import util.Utilities;

public class SyntenyMain 
{
	private static final boolean DEBUG = false;
	
	private Logger mLog;
	private UpdatePool pool;	
	private Project mProj1;
	private Project mProj2;
	private SyProps mProps;
	private int mPairIdx;
	private String mPairDir;
	
	private int checkCtgIdx1 = 0;
	private int checkGrpIdx1 = 0;
	private int checkGrpIdx2 = 0;
	private boolean mSelf = false;

	private int mMaxgap1;
	private int mMaxgap2;
	private int mMingap1;
	private int mMingap2;
	
	private float mAvg1A;
	private float mAvg2A;
	
	private int mMindots; 
	private int mMindots_keepbest; 
	private int mMindotsA; 
	private int mMindotsB; 
	private int mMindotsC; 
	private int mMindotsD; 

	private float mCorr1A; 
	private float mCorr1B; 
	private float mCorr1C; 
	private float mCorr1D; 

	private float mCorr2A; 
	private float mCorr2B; 
	private float mCorr2C; 
	private float mCorr2D; 
	
	private TreeMap<Integer,Integer> mClonesFixed2Score;
	private TreeMap<Integer,Integer> mClonesSinglesFixed2Score;
	private TreeSet<Integer> mClonesToSwap;
	
	private TreeMap<BCase,Integer> mBlksByCase;
	private TreeMap<Integer,Block> mBest;

	private long startTime;
	private static Properties mDBProps;
	private Pattern fieldPat = null;
	boolean bInterrupt = false;

	private static final int CHUNK_SIZE = 1000000; // don't change this or client breaks!! And it appears in several places.

	public SyntenyMain(UpdatePool pool, Logger log, Properties props, SyProps pairProps) {
		this.pool = pool;
		this.mLog = log;
		if (props != null) mDBProps = props;
		assert(pairProps != null);
		mProps = pairProps;
	}
	
	public boolean run(String proj1Name, String proj2Name) 
	throws Exception 
	{
		if (!mProps.getBoolean("do_synteny")) {
			mLog.msg("Skipping synteny finding (do_synteny=0)");
			return true;
		}

		startTime = System.currentTimeMillis();
		
		mLog.msg("\nFinding synteny for " + proj1Name + " and " + proj2Name);
		mProps.printNonDefaulted(mLog);

		mBlksByCase = new TreeMap<BCase,Integer>();
		mBest = new TreeMap<Integer,Block>();
		
		for (BCase bt : BCase.values())
			mBlksByCase.put(bt,0);
		
		ProjType type = pool.getProjType(proj1Name);
		
		String pairName = proj1Name + "_to_" + proj2Name;
		if (type == ProjType.pseudo)
			mPairDir = "data/pseudo_pseudo/" + pairName;
		else
			mPairDir = "data/fpc_pseudo/" + pairName;			

		if (!(new File(mPairDir)).exists()) 
		{
			mLog.msg("Can't find pair directory " + mPairDir);
			ErrorCount.inc();
			return false;
		}
		

		
		mProj1 = new Project(pool, mLog, mProps,proj1Name,type, QueryType.Query);
		mProj2 = new Project(pool, mLog, mProps,proj2Name, ProjType.pseudo, QueryType.Target);
		
		setBlockTestProps();

		mSelf = (mProj1.getIdx() == mProj2.getIdx());

		mPairIdx = Utils.getPairIdx(mProj1.getIdx(), mProj2.getIdx(), pool);
		if (mPairIdx == 0)
		{
			mLog.msg("Can't find project pair in database for " + mProj1.getName() + "," + mProj2.getName());
			ErrorCount.inc();
			return false;
		}
		
		pool.executeUpdate("delete from blocks where pair_idx='" + mPairIdx + "'");
		pool.executeUpdate("delete from ctghits where pair_idx='" + mPairIdx + "'");

		if (mProj1.isFPC())
		{
			if (!mProps.getProperty("checkctg1").equals("0"))
			{
				// TODO checkCtgIdx1 = mProj1.getCtgIdx(Integer.parseInt(mProps.getProperty("checkctg1")));
			}
		}

		if (!mProps.getProperty("checkgrp1").equals("0"))
			checkGrpIdx1 = mProj1.getGrpIdx(mProps.getProperty("checkgrp1"));

		if (!mProps.getProperty("checkgrp2").equals("0"))
			checkGrpIdx2 = mProj2.getGrpIdx(mProps.getProperty("checkgrp2"));
		
		setGapProperties();
		clearBlocks();
		
		if (mProj1.isFPC()) {
			if (mProps.getBoolean("do_bes_fixing")) {
				mLog.msg("BES end adjustment will be performed (set do_bes_fixing=0 to disable)");
				mClonesFixed2Score = new TreeMap<Integer,Integer>();
				mClonesSinglesFixed2Score = new TreeMap<Integer,Integer>();
				mClonesToSwap = new TreeSet<Integer>();
			}
		}
		
		int nGrpGrp = mProj1.getGroups().size() * mProj2.getGroups().size();
		int nGrpGrpStart = nGrpGrp;
		int msgInt = Math.max(1, nGrpGrpStart/10);
		mLog.msg(nGrpGrp + " grp/grp pairs to analyze");
		// Do the grp/grp syntenies, unless we are looking at a particular fpc contig
		if (checkCtgIdx1 == 0) {
			for (Group grp1 : mProj1.getGroups()) {
				if (checkGrpIdx1 > 0 && checkGrpIdx1 != grp1.getIdx()) continue;
				
				for (Group grp2 : mProj2.getGroups()) {
					if (checkGrpIdx2 > 0 && checkGrpIdx2 != grp2.getIdx()) continue;
				
					if (mProj1.isPseudo()) 
					{
						if (!mSelf)
						{
							doPseudoGrpGrpSynteny(grp1,grp2);
							if (bInterrupt) return false;
						}
						else if (grp1.getIdx() <= grp2.getIdx())
						{
							doPseudoGrpGrpSynteny(grp1,grp2);
							if (bInterrupt) return false;
						}
					}
					else
						doFPCGrpGrpSynteny(grp1,grp2);					
					nGrpGrp--;
				
					if (nGrpGrp % msgInt == 0)
					{
						mLog.msg(nGrpGrp + " pairs remaining");
					}
	
				}
			}
		}
		if (mProj1.isPseudo() && mProj2.isPseudo() && mProj1.idx != mProj2.idx)
		{
			setGeneRunCounts();
		}
		if (bInterrupt) return false;
		if (mSelf)
		{
			symmetrizeBlocks();	
		}
		
		// Now do the ctg/grp syntenies, unless we are looking at a particular group
		if (checkGrpIdx1 == 0) {
			if (mProj1.isFPC()) {
				for (FPCContig ctg : mProj1.getFPCData().mCtgs.get(mProj1.getUnanchoredGrpIdx())) {
					if (checkCtgIdx1 > 0 && checkCtgIdx1 != ctg.mIdx) continue;
					
					for (Group grp2 : mProj2.getGroups()) {
						if (checkGrpIdx2 > 0 && checkGrpIdx2 != grp2.getIdx()) continue;
					
						doCtgGrpSynteny(ctg,grp2);
						if (bInterrupt) return false;
					}
				}
			}
		}

		if (mProj1.isFPC())
		{
			uploadBest();
			unanchoredBlocks();
			if (bInterrupt) return false;
			mLog.msg(mClonesToSwap.size() + " BES to swap");
			doCloneSwaps();
		}
		//for (BCase bt : BCase.values())
		//	mLog.msg(bt.toString() + ":" + mBlksByCase.get(bt));
		
		if (mProj1.isPseudo() && mProps.getProperty("no_overlapping_blocks").equals("1"))
		{
			mLog.msg("Removing overlapping blocks");
			TreeMap<Integer,Vector<Block>> seenBlocks = new TreeMap<Integer,Vector<Block>>();
			Vector<Integer> deleteList = new Vector<Integer>();
			// Method - sort by score descending, and drop the blocks which overlap a previous block.
			ResultSet rs = pool.executeQuery("select idx,start1,end1,start2,end2,grp1_idx,grp2_idx," + 
					"blocknum, count(*) as score from blocks join pseudo_block_hits on pseudo_block_hits.block_idx=blocks.idx " + 
					" where pair_idx=" + mPairIdx + " group by pseudo_block_hits.block_idx order by score desc");
			while (rs.next())
			{
				if (bInterrupt) return false;
				int idx = rs.getInt("idx");
				int s1 = rs.getInt("start1");
				int e1 = rs.getInt("end1");
				int s2 = rs.getInt("start2");
				int e2 = rs.getInt("end2");
				int gidx1 = rs.getInt("grp1_idx");
				int gidx2 = rs.getInt("grp2_idx");
				int bnum = rs.getInt("blocknum");
				int score = rs.getInt("score");

				Block newb = new Block(s1, e1, s2, e2, gidx1, gidx2,mPairIdx, mProj1, mProj2, bnum, "");
				boolean delete = false;
				if (!seenBlocks.containsKey(gidx1))
				{
					seenBlocks.put(gidx1, new Vector<Block>());	
				}
				if (!seenBlocks.containsKey(gidx2))
				{
					seenBlocks.put(gidx2, new Vector<Block>());	
				}
				
				for (Block b : seenBlocks.get(gidx1))
				{
					int maxGap = Math.min((e1-s1)/10, (b.mE1-b.mS1)/10);
					if (Utils.intervalsOverlap(s1, e1, b.mS1, b.mE1, -maxGap) )
					{
						delete = true;
						break;
					}
				}
				for (Block b : seenBlocks.get(gidx2))
				{
					int maxGap = Math.min((e2-s2)/10, (b.mE2-b.mS2)/10);
					if (Utils.intervalsOverlap(s2, e2, b.mS2, b.mE2, -maxGap))
					{
						delete = true;
						break;
					}
				}				
				if (delete)
				{
					deleteList.add(idx);
				}
				else
				{
					seenBlocks.get(gidx1).add(newb);	
					seenBlocks.get(gidx2).add(newb);	
				}
			}
			int nDel = deleteList.size();
			if (nDel > 0)
			{
				mLog.msg(nDel + " blocks to delete");
				for (int bidx : deleteList)
				{
					pool.executeUpdate("delete from blocks where idx=" + bidx);	
				}
			}

		}
		if (mProj1.isPseudo())
		{
			for (HitType bt : HitType.values())
			{
				String rlbl = "RawSyHits" + bt.toString();
				String blbl = "BlockHits" + bt.toString();
				
				if (Utils.mStats != null && Utils.mStats.containsKey(rlbl) && Utils.mStats.containsKey(blbl))
				{
					float rnum = Utils.mStats.get(rlbl);
					float bnum = Utils.mStats.get(blbl);
					float pct = (100*bnum)/rnum;
					Utils.incStat("BlockPercent" + bt.toString(), pct);
				}
			}	
		
			if ( mProps.getProperty("print_stats").equals("1"))
			{			
				Utils.dumpHist();
				Utils.dumpStats();
			}
			Utils.uploadStats(pool, mPairIdx, mProj1.idx, mProj2.idx);
		}
		if (bInterrupt) return false;
		if (mProj1.orderedAgainst())
		{
			String orderBy = pool.getProjProp(mProj1.idx, "order_against");
			if (orderBy != null && orderBy.equals(mProj2.getName()))
			{
				orderGroups(false);	
			}	
		}
		else if (mProj2.orderedAgainst())
		{
			String orderBy = pool.getProjProp(mProj2.idx, "order_against");
			if (orderBy != null && orderBy.equals(mProj1.getName()))
			{
				orderGroups(true);	
			}	
		}		
		dumpData();
		
		mLog.msg("Done:  " + Utilities.getDurationString(System.currentTimeMillis()-startTime) + "\n");
		
		return true;
	}
	void interrupt()
	{
		bInterrupt = true;
	}
/*	public static void main(String[] args)
	{
		if (args.length < 2) {
			System.out.println("Usage:  synteny <project1> <project2>\n");
			System.exit(-1);
		}
		
		try {
			mDBProps = new Properties();
			mDBProps.load(new FileInputStream(Utils.getParamsName()));

			String dbstr = DatabaseUser.getDatabaseURL(
					mDBProps.getProperty("db_server"), 
					mDBProps.getProperty("db_name"));
			UpdatePool pool = new UpdatePool(dbstr,
							mDBProps.getProperty("db_adminuser"),
							mDBProps.getProperty("db_adminpasswd"));
			
			SyntenyMain synteny = new SyntenyMain(pool, new Log("symap.log"), null);
			synteny.run(args[0], args[1]);
			DatabaseUser.shutdown();
		}
		catch (Exception e) {
			e.printStackTrace();
			DatabaseUser.shutdown();
			System.exit(-1);
		}
	}
*/	
	private void setGapProperties() throws Exception
	{
		int nHits = 0;
		
		if (mProj1.isPseudo())
		{
			String st = "select count(*) as nhits from pseudo_hits where proj1_idx='" + mProj1.getIdx() + "'" + 
									" and proj2_idx='" + mProj2.getIdx() + "'";
			ResultSet rs = pool.executeQuery(st);
			if (rs.next())
				nHits = rs.getInt("nhits");
			rs.close();
			mLog.msg("Total hits: " + nHits );
		}
		else
		{
			int nBesHits = 0;
			int nMrkHits = 0;
			
			String st = "select count(*) as nhits from bes_hits where proj1_idx='" + mProj1.getIdx() + "'" + 
					" and proj2_idx='" + mProj2.getIdx() + "'";
			ResultSet rs = pool.executeQuery(st);
			if (rs.next())
				nBesHits = rs.getInt("nhits");
			rs.close();
			
			st = "select count(*) as nhits from mrk_hits " + 
				" join markers on markers.name = mrk_hits.marker " + 
				" join mrk_ctg on mrk_ctg.mrk_idx = markers.idx " + 
					" where mrk_hits.pair_idx='" + mPairIdx + "'" +
					" and markers.proj_idx = '" + mProj1.getIdx() + "'";
			rs = pool.executeQuery(st);
			if (rs.next())
				nMrkHits = rs.getInt("nhits");
			rs.close();
			
			nHits = nBesHits + nMrkHits;
			mLog.msg("Total hits: " + nHits + " ; " + nBesHits + " BES, " + nMrkHits + " Marker (counting multiple ctgs)");
		}
		
		if (nHits == 0)
			throw(new Exception("No anchors loaded!"));
		
		mMaxgap1 = mProps.getInt("maxgap1");
		mMaxgap2 = mProps.getInt("maxgap1");
		mAvg1A = mProps.getFloat("avg1_A");
		mAvg2A = mProps.getFloat("avg2_A");
		
		if (mMaxgap1 == 0)
			mMaxgap1 = (int)( ((float)mProj1.getSizeBP())/(Math.sqrt(nHits)));
		if (mMaxgap2 == 0)
			mMaxgap2 = (int)( ((float)mProj2.getSizeBP())/(Math.sqrt(nHits)));
		if (mAvg1A == 0)
			mAvg1A = ((float)mMaxgap1)/15;
		if (mAvg2A == 0)
			mAvg2A = ((float)mMaxgap2)/15;
		
		mProps.setProperty("maxgap1", String.valueOf(mMaxgap1));
		mProps.setProperty("maxgap2", String.valueOf(mMaxgap2));
		mProps.setProperty("avg1_A", String.valueOf(mAvg1A));
		mProps.setProperty("avg2_A", String.valueOf(mAvg2A));

		//mLog.msg("maxgap1:" + mMaxgap1 + " maxgap2:" + mMaxgap2);
		//mLog.msg("avg1_A:" + mAvg1A + " avg2_A:" + mAvg2A);
	}
	
	
	private void doPseudoGrpGrpSynteny(Group grp1, Group grp2) throws Exception
	{
		Vector<SyHit> hits = new Vector<SyHit>();
		
		boolean isSelf = (grp1.getIdx() == grp2.getIdx());
		
      	String st = "SELECT h.idx, h.start1, h.end1, h.start2, h.end2, h.pctid,h.gene_overlap" +
            " FROM pseudo_hits as h " +  
            " WHERE h.proj1_idx='" + mProj1.getIdx() + "'" + 
            " AND h.proj2_idx='" +  mProj2.getIdx() + "'" +  
            " AND h.grp1_idx='" + grp1.getIdx() + "'" + 
            " AND h.grp2_idx='" + grp2.getIdx() + "'" ;
      	
      	if (isSelf)
      	{
      		st += " AND h.start1 < h.start2 "; // do blocks in one triangle, mirror later
      	}
		ResultSet rs = pool.executeQuery(st);
		while (rs.next())
		{
			int start2 = rs.getInt("start2");
			int end2 = rs.getInt("end2");
			int start1 = rs.getInt("start1");
			int end1 = rs.getInt("end1");

			int pos1 = (start1 + end1)/2;
			int pos2 = (start2 + end2)/2;
			int gene = rs.getInt("gene_overlap");
			
			// Ignore diagonal hits for self-alignments
			// This doesn't really work though because tandem gene families create many near-diagonal hits. 
			if (isSelf ) 
			{
				if (pos1 >= pos2) continue; // we will find only upper triangle blocks, and reflect them later.
				if (Utils.intervalsOverlap(start1, end1, start2, end2, 0))
				{
					continue;
				}
			}
			
			
			hits.add(new SyHit(pos1,pos2,rs.getInt("idx"),SyHitType.Pseudo,rs.getInt("pctid"),gene));
			Utils.incStat("SyntenyHitsTotal", 1);
			if (gene == 0)
			{
				Utils.incStat("RawSyHits" + HitType.NonGene.toString(), 1);
			}
			else if (gene == 1)
			{
				Utils.incStat("RawSyHits" + HitType.GeneNonGene.toString(), 1);
			}
			if (gene == 2)
			{
				Utils.incStat("RawSyHits" + HitType.GeneGene.toString(), 1);
			}			
		}
		rs.close();
		
		int nhits = hits.size();
		//if (nhits > 0)
		//	mLog.msg("grp:grp " + grp1.getName() + ":" + grp2.getName() + " loaded " + nhits + " hits");
			
		Vector<Block> blocks = new Vector<Block>();
		
		chainFinder(hits,blocks,null);
		if (isSelf)
		{
			blocks = removeDiagonalBlocks(blocks);
		}
		blocks = mergeBlocks(blocks);
		setBlockGrps(blocks,grp1.getIdx(),grp2.getIdx());
		int num = 1;			
		for (Block b : blocks)
		{
			b.mType = BlockType.PseudoPseudo;
			b.mNum = num++;
		}
		uploadBlocks(blocks);
	}
	
	private Vector<Block> removeDiagonalBlocks(Vector<Block> in)
	{
		Vector<Block> out = new Vector<Block>();
		for (Block b : in)
		{
			if (!Utils.intervalsOverlap(b.mS1, b.mE1, b.mS2, b.mE2, 0))
			{
				out.add(b);
			}
		}
		return out;
	}

	private void doFPCGrpGrpSynteny(Group grp1, Group grp2) throws Exception
	{
		Vector<SyHit> hits = new Vector<SyHit>();
		
		int nMrk = 0;
		int nBES = 0;
		String st;
		
		// Markers
      	st = "select mrk_hits.idx as hidx, markers.idx as midx, contigs.idx as ctgidx," + 
		" mrk_hits.start2, mrk_hits.end2, mrk_hits.pctid, (contigs.ccb+mrk_ctg.pos) as pos1" +
		" from mrk_hits " +
		" join markers on markers.name=mrk_hits.marker" +
		" join mrk_ctg on mrk_ctg.mrk_idx=markers.idx " +
		" join contigs on contigs.idx=mrk_ctg.ctg_idx " +
		" where contigs.grp_idx='" + grp1.getIdx() + "'" +
		" and markers.proj_idx='" + mProj1.getIdx() + "'" +
		" and mrk_hits.grp2_idx = '" + grp2.getIdx() + "'" +
		" and mrk_hits.proj1_idx = '" + mProj1.getIdx() + "'";

		ResultSet rs = pool.executeQuery(st);
		while (rs.next())
		{
			int pos1 = rs.getInt("pos1");
			int pos2 = (rs.getInt("start2") + rs.getInt("end2"))/2;
			SyHit sh = new SyHit(pos1,pos2,rs.getInt("hidx"),SyHitType.Mrk,rs.getInt("pctid"),0);
			
			sh.mCtgIdx1 = rs.getInt("ctgidx");
			hits.add(sh);
			nMrk++;
		}
		rs.close();

		// BES
      	st = "select bes_hits.idx as hidx, bes_hits.bes_type, contigs.idx as ctgidx," +
      			" bes_hits.start2, bes_hits.end2, contigs.ccb, " +
      			"bes_hits.pctid, bes_hits.evalue as score," +
      			"clones.cb1, clones.cb2,  " +
      			" clones.bes1, clones.bes2,clones.idx as clone_idx " +
      		" from bes_hits " +
      		" join clones on clones.name=bes_hits.clone " +
      		" join contigs on contigs.idx=clones.ctg_idx " +
      		" where contigs.grp_idx='" + grp1.getIdx() + "'" +
      		" and bes_hits.proj1_idx='" + mProj1.getIdx() + "'" +
      		" and bes_hits.proj2_idx='" + mProj2.getIdx() + "'" +
      		" and bes_hits.grp2_idx = '" + grp2.getIdx() + "'";
		rs = pool.executeQuery(st);
		while (rs.next())
		{
			int pos1;
			int pos1_alt;
			if (rs.getString("bes_type").equals(rs.getString("bes1")))
			{
				pos1 = rs.getInt("cb1") + rs.getInt("ccb");
				pos1_alt = rs.getInt("cb2") + rs.getInt("ccb");
			}
			else
			{
				pos1 = rs.getInt("cb2") + rs.getInt("ccb");					
				pos1_alt = rs.getInt("cb1") + rs.getInt("ccb");
			}
			int pos2 = (rs.getInt("start2") + rs.getInt("end2"))/2;
			SyHit sh = new SyHit(pos1,pos2,rs.getInt("hidx"),SyHitType.Bes,rs.getInt("pctid"),0);
			sh.mCtgIdx1 = rs.getInt("ctgidx");
			sh.mCloneIdx1 = rs.getInt("clone_idx");
			sh.mRF = RF.valueOf(rs.getString("bes_type").toUpperCase());
			assert sh.mRF != null;
			sh.mScore = rs.getInt("score");
			sh.mPos1_alt = pos1_alt;
			hits.add(sh);
			nBES++;
		}
		rs.close();

		int nhits = hits.size();
		//if (nhits > 0)
		//	mLog.msg("grp:grp " + grp1.getName() + ":" + grp2.getName() + " loaded " + nhits + " hits (marker:" + nMrk + ";bes:" + nBES + ")");
			
		Vector<Block> blocks = new Vector<Block>();
		
		chainFinder(hits,blocks,null);	
		for (Block b : blocks)
			fixBES(b);
		blocks = mergeBlocks(blocks);

		setBlockGrps(blocks,grp1.getIdx(),grp2.getIdx());
		for (Block b : blocks)
			mProj1.getFPCData().setBlockContigs(b);

		int num = 1;
		for (Block b : blocks)
		{
			b.mType = BlockType.GrpPseudo;
			b.mNum = num;
			uploadCtgWindows(b,mProj1.getFPCData());
			num++;
		}		
		uploadBlocks(blocks);
	}
	
	private void doCtgGrpSynteny(FPCContig ctg1, Group grp2) throws SQLException
	{
		Vector<SyHit> hits = new Vector<SyHit>();
		
		int nMrk = 0;
		int nBES = 0;
		String st;
		
		// Markers
      	st = "select mrk_hits.idx as hidx, markers.idx as midx, " + 
      		" mrk_hits.start2, mrk_hits.end2, mrk_ctg.pos as pos1,mrk_hits.pctid" +
      		" from mrk_hits " +
      		" join markers on markers.name=mrk_hits.marker" +
			" join mrk_ctg on mrk_ctg.mrk_idx=markers.idx " +
			" where mrk_ctg.ctg_idx='" + ctg1.mIdx + "'" + 
			" and markers.proj_idx='" + mProj1.getIdx() + "'" +
			" and mrk_hits.grp2_idx = '" + grp2.getIdx() + "'" +
			" and mrk_hits.proj1_idx = '" + mProj1.getIdx() + "'";
		ResultSet rs = pool.executeQuery(st);
		while (rs.next())
		{
			int pos1 = rs.getInt("pos1");
			int pos2 = (rs.getInt("start2") + rs.getInt("end2"))/2;
			SyHit sh = new SyHit(pos1,pos2,rs.getInt("hidx"),SyHitType.Mrk,rs.getInt("pctid"),0);
			sh.mCtgIdx1 = ctg1.mIdx;
			hits.add(sh);
			nMrk++;
		}
		rs.close();

		// BES
      	st = "select bes_hits.idx as hidx, bes_hits.bes_type,bes_hits.pctid, bes_hits.evalue as score," +
      			" bes_hits.start2, bes_hits.end2, clones.cb1, clones.cb2, " +
      			" clones.bes1, clones.bes2, clones.idx as clone_idx " +
      		" from bes_hits " +
      		" join clones on clones.name=bes_hits.clone " +
      		" where clones.ctg_idx='" + ctg1.mIdx + "'" +
      		" and bes_hits.proj1_idx='" + mProj1.getIdx() + "'" +
      		" and bes_hits.proj2_idx='" + mProj2.getIdx() + "'" +
      		" and bes_hits.grp2_idx = '" + grp2.getIdx() + "'";
		rs = pool.executeQuery(st);
		while (rs.next())
		{
			int pos1;
			int pos1_alt;
			if (rs.getString("bes_type").equals(rs.getString("bes1"))) {
				pos1 = rs.getInt("cb1");
				pos1_alt = rs.getInt("cb2");
			}
			else {
				pos1 = rs.getInt("cb2");	
				pos1_alt = rs.getInt("cb1");
			}
			int pos2 = (rs.getInt("start2") + rs.getInt("end2"))/2;
			SyHit sh = new SyHit(pos1,pos2,rs.getInt("hidx"),SyHitType.Bes,rs.getInt("pctid"),0);
			sh.mCtgIdx1 = ctg1.mIdx;
			sh.mCloneIdx1 = rs.getInt("clone_idx");
			sh.mRF = RF.valueOf(rs.getString("bes_type").toUpperCase());
			assert sh.mRF != null;
			sh.mScore = rs.getInt("score");
			sh.mPos1_alt = pos1_alt;
			hits.add(sh);
			nBES++;
		}
		rs.close();

		int nhits = hits.size();
		//if (nhits > 0)
		//	mLog.msg("ctg:grp " + ctg1.mNum + ":" + grp2.getName() + " loaded " + nhits + " hits (marker:" + nMrk + ";bes:" + nBES + ")");
			
		Vector<Block> blocks = new Vector<Block>();
		
		Block bestblk = new Block(mPairIdx,mProj1,mProj2);
		bestblk.mGrpIdx1 = mProj1.getUnanchoredGrpIdx();
		bestblk.mGrpIdx2 = grp2.getIdx();
		
		chainFinder(hits,blocks,bestblk);

		if (bestblk.mHits.size() > 0)
		{
			if (!mBest.containsKey(ctg1.mIdx) ||
					mBest.get(ctg1.mIdx).mHits.size() < bestblk.mHits.size())
			{
				mBest.put(ctg1.mIdx,bestblk);
			}
		}
		for (Block b : blocks)
			fixBES(b);
		
		blocks = mergeBlocks(blocks);

		setBlockGrps(blocks,mProj1.getUnanchoredGrpIdx(),grp2.getIdx());

		int num = 1;
		for (Block b : blocks)
		{
			b.mCtgs1 = String.valueOf(ctg1.mIdx);
			b.mType = BlockType.CtgPseudo;
			b.mNum = num;
			uploadCtgWindows(b,mProj1.getFPCData());
			num++;
		}
	}
	
	// This routine organizes the "binary search" of the gap parameter space
	//
	// How it works:
	// 1. Progressively smaller initial gap values are examined, maxgap, maxgap/2, maxgap/4, ...
	// 2. If a good block is found, then the range [gap, 2*gap] is searched with a binary
	//		search to find the largest gap giving a good block. 
	// 3. The highest-scoring chain at the maximum gap param is put into the "best" array
	// 		to provide at least one result for every query
	private void chainFinder(Vector<SyHit> hits, Vector<Block> blocks, Block bestblk)
	{
		Collections.sort(hits,new SyntenyMain.SyHitComp());
		
		for (int i = 0; i < hits.size(); i++)
			hits.get(i).mI = i;

		if (bestblk != null)
		{
			longestChain(mMaxgap1/2,mMaxgap2/2,mMindots_keepbest,hits, bestblk);
			resetDPUsed(hits);
		}

		// Set the decreasing gap search points
		Vector<Integer> gap1list = new Vector<Integer>();
		Vector<Integer> gap2list = new Vector<Integer>();
						
		gap1list.add(mMaxgap1/2);
		gap2list.add(mMaxgap2/2);
		
		int ngaps = 1;
		while (gap1list.get(ngaps-1) >= 2*mMingap1 && gap2list.get(ngaps-1) >= 2*mMingap2)
		{
			gap1list.add(gap1list.get(ngaps-1)/2);
			gap2list.add(gap2list.get(ngaps-1)/2);	
			ngaps++;
		}

		for (int i = 0; i < ngaps; i++)
		{
			int low1 = gap1list.get(i);
			int low2 = gap2list.get(i);
			int high1 = (i > 0 ? gap1list.get(i-1) : low1);
			int high2 = (i > 0 ? gap2list.get(i-1) : low2);
			if (DEBUG) mLog.msg("NEXT GAP PAIR " + low1 + "," + high1 + " " + low2 + "," + high2);

			while (goodChainsExist(low1,low2,hits))
				binarySearch(low1,high1,low2,high2,mMingap1, mMingap2, hits,blocks);				
		}
	}
	
	private void resetDPUsed(Vector<SyHit> hits)
	{
		for (SyHit h : hits)
			h.mDPUsed = h.mDPUsedFinal;
	}
	
	private void binarySearch(int low1,int high1, int low2, int high2, 
			int min1, int min2,	Vector<SyHit> hits, Vector<Block> blocks)
	{

		int gap1 = (high1 + low1)/2;
		int gap2 = (high2 + low2)/2;
		
		while ( high1-low1 > min1 && high2-low2 > min2)
		{
			if (DEBUG) mLog.msg("SEARCH:" + low1 + "-" + gap1 + "-" + high1 + " " + low2 + "-" + gap2 + "-" + high2);

			if (goodChainsExist(gap1,gap2,hits))
			{
				if (DEBUG) mLog.msg("found good " + gap1 + " " + gap2);
				low1 = gap1;
				low2 = gap2;
				gap1 = (gap1 + high1)/2;
				gap2 = (gap2 + high2)/2;
			}
			else
			{
				if (DEBUG) mLog.msg("no good " + gap1 + " " + gap2);
				
				high1 = gap1;
				high2 = gap2;
				gap1 = (low1 + gap1)/2;
				gap2 = (low2 + gap2)/2;
			}
		}
		// low1,2 had good blocks
		cullChains(low1,low2,hits,blocks);
	}
	
	private void cullChains(int gap1,int gap2, Vector<SyHit> hits, Vector<Block> blocks)
	{
		Block blk = new Block(mPairIdx,mProj1,mProj2);
		if (DEBUG) mLog.msg("GAP1:" + gap1 + " GAP2:" + gap2 + " CULL");
		while(longestChain(gap1,gap2,mMindots_keepbest,hits,blk))
		{
			if (goodBlock(blk, hits))
			{
				blocks.add(blk);
				if (DEBUG) mLog.msg("BLK " + blk.mCase.toString() + " score:" + blk.mHits.size() + " (" + blk.mS1 + "," + blk.mE1 + ";" + blk.mS2 + "," + 
					blk.mE2 + ") corr(" + blk.mCorr1 + "," + blk.mCorr2 + ")" + " gap1:" + gap1 + " gap2:" + gap2);
				for (SyHit h : blk.mHits)
					h.mDPUsedFinal = true;
				mBlksByCase.put(blk.mCase,mBlksByCase.get(blk.mCase)+1);
			}
			blk = new Block(mPairIdx,mProj1,mProj2); 
		}
		resetDPUsed(hits);
	}
	
	private boolean goodChainsExist(int gap1,int gap2, Vector<SyHit> hits)
	{
		Block blk = new Block(mPairIdx,mProj1,mProj2);
		while(longestChain(gap1,gap2,mMindots_keepbest,hits,blk))
		{
			if (goodBlock(blk, hits))
			{
				resetDPUsed(hits);
				return true;
			}
			blk = new Block(mPairIdx,mProj1,mProj2);
		}
		resetDPUsed(hits);
		return false;
	}
	
	private boolean goodBlock(Block blk, Vector<SyHit> hits)
	{
		int score = blk.mHits.size();
		
		if (score < mMindotsA) return false;
		
		blockCorrelations(blk, hits);
		
		float avg1 = (blk.mE1 - blk.mS1)/score;
		float avg2 = (blk.mE2 - blk.mS2)/score;
		
		if (score >= mMindotsA && avg1 <= mAvg1A && avg2 <= mAvg2A
				&& Math.abs(blk.mCorr1) >= mCorr1A 
				&& Math.abs(blk.mCorr2) >= mCorr2A)
		{
			blk.mCase = BCase.A;
		}
		else if (score >= mMindotsB	
					&& Math.abs(blk.mCorr1) >= mCorr1B 
					&& Math.abs(blk.mCorr2) >= mCorr2B)
		{
			blk.mCase = BCase.B;
		}
		else if (score >= mMindotsC	
					&& Math.abs(blk.mCorr1) >= mCorr1C 
					&& Math.abs(blk.mCorr2) >= mCorr2C)
		{
			blk.mCase = BCase.C;
		}
		else if (score >= mMindotsD	
					&& Math.abs(blk.mCorr1) >= mCorr1D 
					&& Math.abs(blk.mCorr2) >= mCorr2D)
		{
			blk.mCase = BCase.D;
		}
		
		return (blk.mCase != BCase.Reject);
	}
	
	private void blockCorrelations(Block blk, Vector<SyHit> hits)
	{
		blk.mCorr1 = hitCorrelation(blk.mHits);
		
		Vector<SyHit> hits2 = new Vector<SyHit>();
		for (SyHit h : hits)
		{
			if (h.mPos1 >= blk.mS1 && h.mPos1 <= blk.mE1 &&
					h.mPos2 >= blk.mS2 && h.mPos2 <= blk.mE2)
			{
				hits2.add(h);
			}
		}
		blk.mCorr2 = hitCorrelation(hits2);
//		blk.print(mLog,"check:");
	}
	
	private float hitCorrelation(Vector<SyHit> hits)
	{
		double N = (double)hits.size();
		
		if (N <= 2) return 0;

		double xav 		= 0;
		double xxav 	= 0;
		double yav 		= 0;
		double yyav 	= 0;
		double xyav 	= 0;
		
		for (SyHit h : hits)
		{
			double x = (double)h.mPos1;
			double y = (double)h.mPos2;
			
			xav 	+= x;
			yav 	+= y;
			xxav 	+= x*x;
			yyav 	+= y*y;
			xyav 	+= x*y;
		}
		
		xav 	/= N;
		yav 	/= N;
		xxav 	/= N;
		yyav 	/= N;
		xyav	/= N;
		
		double sdx = Math.sqrt(Math.abs(xxav - xav*xav));
		double sdy = Math.sqrt(Math.abs(yyav - yav*yav));
		double sdprod = sdx*sdy;
		
		double corr = 0;
		
		if (sdprod > 0)
			corr = ((xyav - xav*yav)/sdprod);
		assert(Math.abs(corr) < 1.01); // should not be a larger roundoff error than this
		if (Math.abs(corr) > 1)
		{
			corr = (corr > 0 ? 1.0 : -1.0);
		}

		return (float)corr;
	}
	
	// The core dynamic programming routine
	// Return the longest chain, if any, with at least mindots dots
	// The DPUsed flags are set on the hits returned, so the routine can
	// be run repeatedly, pulling out the successively largest chains.
	// resetDPUsed should be called on the hit list before starting the process over.
	private boolean longestChain(int gap1,int gap2,int mindots,
				Vector<SyHit> hits, Block blk)
	{
		if (hits.size() < mindots) return false;
		
		for (SyHit hit : hits)
		{
			hit.mNDots 		= 1;
			hit.mDPScore 	= 1;
			hit.mPrevI 		= -1;
		}
		SyHit overall_best_hit = hits.get(0);
		for (int i = 0; i < hits.size(); i++)
		{
			SyHit h1 = hits.get(i);
			if (h1.mDPUsed) continue;
			
			Vector<SyHit> links = new Vector<SyHit>();
			
			for (int j = i-1; j >= 0; j--)
			{
				SyHit h2 = hits.get(j);
				if (h2.mDPUsed) continue;
				if (h1.mPos2 - h2.mPos2 > mMaxgap2) break; // crucial optimization from sorting on pos2
				if (Math.abs(h1.mPos1 - h2.mPos1) > mMaxgap1) continue;	
				
				links.add(h2);
				//Log.msg("link " + i + "," + j);				
			}
			if (links.size() > 0)
			{
				int maxscore = 0;
				SyHit hbest = null;
				for (SyHit h : links)
				{
					float d1 = Math.abs(((float)h.mPos1 - (float)h1.mPos1)/(float)gap1);
					float d2 = Math.abs(((float)h.mPos2 - (float)h1.mPos2)/(float)gap2);
					int gap_penalty = (int)(d1 + d2);
					int dpscore = 1 + h.mDPScore - gap_penalty;
					if (dpscore > maxscore)
					{
						maxscore = dpscore;
						hbest = h;
					}
				}
				if (maxscore > 0)
				{
					h1.mDPScore = maxscore;
					h1.mPrevI = hbest.mI;
					h1.mNDots += hbest.mNDots;
					
					if (h1.mDPScore > overall_best_hit.mDPScore)
					{
						overall_best_hit = h1;
					}
					else if (h1.mDPScore >= overall_best_hit.mDPScore
							&& h1.mNDots > overall_best_hit.mNDots)
					{
						overall_best_hit = h1;
					}					
				}
				else
				{
					h1.mDPScore 	= 1;
					h1.mPrevI 		= -1;
					h1.mNDots 		= 1;
				}
			}
		}
		if (overall_best_hit.mNDots < mindots)
			return false;

		SyHit h = overall_best_hit;
		//Log.msg("best terminus:" + h.mI + " score:" + h.mNDots);
		
		int s1 = h.mPos1;
		int e1 = h.mPos1;
		int s2 = h.mPos2;
		int e2 = h.mPos2;
				
		while (true)
		{
			h.mDPUsed = true;
			//Log.msg("block hit:" + h.mI);
			
			blk.mHits.add(h);
			
			s1 = Math.min(s1, h.mPos1);
			s2 = Math.min(s2, h.mPos2);
			e1 = Math.max(e1, h.mPos1);
			e2 = Math.max(e2, h.mPos2);
			
			if (h.mPrevI >= 0)
				h = hits.get(h.mPrevI);
			else
				break;
		}
		
		blk.mS1 = s1;
		blk.mS2 = s2;
		blk.mE1 = e1;
		blk.mE2 = e2;
		return true;
	}

	private void setBlockTestProps() throws Exception
	{
		mMindots = mProps.getInt("mindots");
		mMindots_keepbest = mProps.getInt("mindots_keepbest");
		mMingap1 = (mProj1.isPseudo() ? 
							mProps.getInt("mingap1") :
							mProps.getInt("mingap1_cb"));
		mMingap2 = mProps.getInt("mingap2");
		mMindotsA = mMindots;//mProps.getInt("mindots_A"); 		// mdb changed 9/4/09
		mMindotsB = mMindots;//mProps.getInt("mindots_B"); 		// mdb changed 9/4/09 
		mMindotsC = 3*mMindots/2;//mProps.getInt("mindots_C"); 	// mdb changed 9/4/09 
		mMindotsD = 2*mMindots;//mProps.getInt("mindots_D"); 	// mdb changed 9/4/09 

		mCorr1A = mProps.getFloat("corr1_A"); 
		mCorr1B = mProps.getFloat("corr1_B"); 
		mCorr1C = mProps.getFloat("corr1_C"); 
		mCorr1D = mProps.getFloat("corr1_D"); 

		mCorr2A = mProps.getFloat("corr2_A"); 
		mCorr2B = mProps.getFloat("corr2_B"); 
		mCorr2C = mProps.getFloat("corr2_C"); 
		mCorr2D = mProps.getFloat("corr2_D"); 

		if (mMindots <= 0 || mMindotsA <= 0 || mMindotsB <= 0 
				|| mMindotsC <= 0 || mMindotsD <= 0 || mMindots_keepbest <= 0)
		{
			throw(new Exception("Min dots parameter(s) out of range (<=0)"));
		}
	}
	
	private void setBlockGrps(Vector<Block> blocks, int gidx1, int gidx2)
	{
		for (Block b : blocks)
		{
			b.mGrpIdx1 = gidx1;
			b.mGrpIdx2 = gidx2;
		}
	}
	
	private void uploadBlocks(Vector<Block> blocks) throws Exception
	{
		if (!Utilities.tableHasColumn("blocks", "score",pool.createStatement() ))
		{
			System.out.println("Updating blocks table");
			pool.executeUpdate("alter table blocks add score integer default 0 after corr");
			pool.executeUpdate("update blocks set score=(select count(*) from pseudo_block_hits where block_idx=idx)");
		}
		
		for (Block b : blocks)
		{
			if (mProj1.isFPC())
			{
				if (b.mGrpIdx1 != mProj1.getUnanchoredGrpIdx())
				{
					b.doUpload(pool);
				}
			}
			else
			{
				b.doUpload(pool);	
			}
			
			if (mProj1.isPseudo())
			{
				if (b.mHits.size() == 0)
				{
					//mLog.msg("empty block!!");	
				}
				uploadPseudoBlockHits(b);
			}
		}
		if (mProj1.isPseudo())
		{
			Utils.updateGeneFractions(pool, mPairIdx);
		}

	}
	
	private void clearBlocks() throws SQLException
	{	
		String stmt = "DELETE FROM blocks WHERE pair_idx=" + mPairIdx;
		pool.executeUpdate(stmt);
	}
	
	// Get the contig hit windows from the blocks and upload
	// Also, upload the block hits at this time, because they are
	// indexed to the contig hit windows
	private void uploadCtgWindows(Block b, FPCData f) throws SQLException
	{
		TreeMap<Integer,CtgHit> ctgHitMap = new TreeMap<Integer,CtgHit>();
		for (SyHit h : b.mHits)
		{
			if (!ctgHitMap.containsKey(h.mCtgIdx1))
				ctgHitMap.put(h.mCtgIdx1, new CtgHit(h.mCtgIdx1, b.mGrpIdx2, mPairIdx));
			ctgHitMap.get(h.mCtgIdx1).addHit(h);		
		}
		for (CtgHit ch : ctgHitMap.values())
		{
			ch.mCorr = (hitCorrelation(ch.mHits) > 0 ? "+" : "-");
			ch.mBlockNum = b.mNum;
			ch.doUpload(pool);
		}
	}
	
	private void uploadPseudoBlockHits(Block b) throws SQLException
	{
		for (SyHit h : b.mHits)
		{
			Vector<String> vlist = new Vector<String>();
			vlist.add(String.valueOf(h.mIdx));
			vlist.add(String.valueOf(b.mIdx));
			
			pool.bulkInsertAdd("pseudo_block_hits", vlist);
			
			Utils.incStat("BlockHits" + h.mBT.toString(), 1);

		}
		pool.finishBulkInserts();
	}
	
//	private void printBlocks(Vector<Block> blocks)
//	{
//		for (Block b : blocks)
//			b.print(mLog,"BLK:");
//	}
	
	private Vector<Block> mergeBlocks(Vector<Block> blocks)
	{
		int nprev = blocks.size() + 1;
//		printBlocks(blocks);
		while (nprev > blocks.size())
		{
			nprev = blocks.size();
			blocks = mergeBlocksSingleRound(blocks);
//			int nchange = nprev - blocks.size();
//			System.out.println("merge " + nchange + " blocks");
//			printBlocks(blocks);
		}
		return blocks;
	}
	
	private Vector<Block> mergeBlocksSingleRound(Vector<Block> blocks)
	{
		if (blocks.size() <= 1) return blocks;
		boolean doMerge = 1 == mProps.getInt("merge_blocks");
		int maxjoin1 = 0;
		int maxjoin2 = 0;
		float joinfact = 0;
		if (doMerge)
		{		
			maxjoin1 = (mProj1.isFPC() ? mProps.getInt("maxjoin_cb") : mProps.getInt("maxjoin_bp"));
			maxjoin2 = mProps.getInt("maxjoin_bp");
			joinfact = mProps.getFloat("joinfact");
		}
		
		SyGraph graph = new SyGraph(blocks.size());
		
		for (int i = 0; i < blocks.size(); i++)
		{
			Block b1 = blocks.get(i);
			
			int w11 = b1.mE1 - b1.mS1;
			int w12 = b1.mE2 - b1.mS2;				
			
			for (int j = i + 1; j < blocks.size(); j++)
			{
				Block b2 = blocks.get(j);

				int w21 = b2.mE1 - b2.mS1;
				int w22 = b2.mE2 - b2.mS2;

				int gap1 = Math.min(maxjoin1,(int)(joinfact*Math.max(w11,w21)));
				int gap2 = Math.min(maxjoin2,(int)(joinfact*Math.max(w12,w22)));
				
				if (doMerge)
				{
					if (Utils.intervalsOverlap(b1.mS1,b1.mE1,b2.mS1,b2.mE1,gap1) && 
							Utils.intervalsOverlap(b1.mS2,b1.mE2,b2.mS2,b2.mE2,gap2) )
					{
						graph.addNode(i,j);
					}
				}
				else
				{
					// only merge if contained	
					if (Utils.intervalContained(b1.mS1,b1.mE1,b2.mS1,b2.mE1) && 
							Utils.intervalContained(b1.mS2,b1.mE2,b2.mS2,b2.mE2) )
					{
						graph.addNode(i,j);
					}					
				}
			}
		}
		HashSet<TreeSet<Integer>> blockSets = graph.transitiveClosure();
		
		Vector<Block> mergedBlocks = new Vector<Block>();
		
		for (TreeSet<Integer> s : blockSets)
		{
			Block bnew = null;
			for (Integer i : s)
			{
				if (bnew == null) 
					bnew = blocks.get(i);
				else
					bnew.mergeWith(blocks.get(i));
			}
			mergedBlocks.add(bnew);
		}
		
		return mergedBlocks;
	}
	
	// Form "blocks" by overlapping the target alignment regions of unanchored contigs
	private void unanchoredBlocks() throws SQLException
	{
		mLog.msg("Aligning unanchored contigs");
		for (Group grp2 : mProj2.getGroups())
			unanchoredBlocksToGroup(grp2);
	}
	
	private void unanchoredBlocksToGroup(Group grp2) throws SQLException
	{
		Vector<CtgHit> ctgHits = new Vector<CtgHit>();
		
		String st = "SELECT ctghits.idx, ctg1_idx,start2,end2,score,contigs.size as csize " +
            		" FROM ctghits, contigs " +
            		" WHERE contigs.proj_idx = '" + mProj1.getIdx() + "'" +
            		" AND contigs.grp_idx = '" + mProj1.getUnanchoredGrpIdx() + "'" +
            		" AND contigs.idx = ctghits.ctg1_idx " +
            		" AND ctghits.grp2_idx = '" + grp2.getIdx() + "'";
		ResultSet rs = pool.executeQuery(st);
		while (rs.next())
		{
			ctgHits.add(new CtgHit(rs.getInt("ctg1_idx"), rs.getInt("start2"), 
							rs.getInt("end2"),rs.getInt("score"), rs.getInt("csize")) );
		}
		rs.close();
		
		//mLog.msg(ctgHits.size() + " unanchored alignments to grp " + grp2.getName());
		
		// first group the ctg windows by doing a transitive closure on the target intervals
		SyGraph graph = new SyGraph(ctgHits.size());
		int gap = mProps.getInt("unanch_join_dist_bp");
		for (int i = 0; i < ctgHits.size(); i++)
		{
			CtgHit ch1 = ctgHits.get(i);
						
			for (int j = i + 1; j < ctgHits.size(); j++)
			{
				CtgHit ch2 = ctgHits.get(j);

				if (Utils.intervalsOverlap(ch1.mS2,ch1.mE2,ch2.mS2,ch2.mE2,gap) )
					graph.addNode(i,j);
			}
		}
		HashSet<TreeSet<Integer>> blockSets = graph.transitiveClosure();

		// compute the block sizes and the order of the contigs within them
		// if a contig appears multiple times in one, use its highest scoring location
		// lastly, upload and number the blocks
		int bnum = 1;
		for (TreeSet<Integer> s : blockSets)
		{
			int s2 = Integer.MAX_VALUE;
			int e2 = 0;
			TreeMap<Integer,CtgHit> ctgLoc = new TreeMap<Integer,CtgHit>();
			for (Integer i : s)
			{
				CtgHit ch = ctgHits.get(i);
				s2 = Math.min(s2,ch.mS2);
				e2 = Math.max(e2,ch.mE2);
				int ctgIdx = ch.mCtgIdx1;
				if (!ctgLoc.containsKey(ctgIdx) || ctgLoc.get(ctgIdx).mScore < ch.mScore)
				{
					ctgLoc.put(ctgIdx, ch);
				}
			}
			Vector<CtgHit> ctgOrder = new Vector<CtgHit>(ctgLoc.values());
			Collections.sort(ctgOrder,new SyntenyMain.ctgLocComp());
			Vector<String> ctgList = new Vector<String>();
			int s1 = 0;
			int e1 = 0;
			for (CtgHit ch : ctgOrder)
			{
				ctgList.add(String.valueOf(ch.mCtgIdx1));
				e1 += ch.mCtgSize; // set the "end" coordinate to give the right size
			}
			String ctgStr = Utils.join(ctgList,",");
			Block blk = new Block(s1, e1, s2, e2, mProj1.getUnanchoredGrpIdx(), grp2.getIdx(),mPairIdx, mProj1,mProj2, bnum,ctgStr);
			
			blk.doUpload(pool);
			
			for (Integer i : s)
			{
				int ctgHitIdx = ctgHits.get(i).mIdx;
				st = "update ctghits set block_num='" + String.valueOf(bnum) + "' where idx='" + String.valueOf(ctgHitIdx) + "'";
				pool.executeUpdate(st);
			}
			
			bnum++;
		}
	}
	
	// Find the best chain for each contig for which we don't already have a ctghit. 
	private void uploadBest() throws SQLException
	{
		if (!mProps.getBoolean("keep_best")) return;
		mLog.msg("Adding best contig alignments (since keep_best=1)");
		
		TreeSet<Integer> ctgIdxUsed = new TreeSet<Integer>();
		
		String st = "SELECT ctg1_idx " +
            		" FROM ctghits, contigs, groups " +
            		" WHERE contigs.proj_idx = '" + mProj1.getIdx() + "'" +
            		" AND contigs.grp_idx = '" + mProj1.getUnanchoredGrpIdx() + "'" +
            		" AND contigs.idx = ctghits.ctg1_idx " +
            		" AND groups.proj_idx = '" + mProj2.getIdx() + "'" +
            		" AND ctghits.grp2_idx = groups.idx";
		ResultSet rs = pool.executeQuery(st);
		while (rs.next())
			ctgIdxUsed.add(rs.getInt("ctg1_idx"));
		rs.close();
		
		for (int ctgIdx : mBest.keySet())
			if (!ctgIdxUsed.contains(ctgIdx))
				uploadCtgWindows(mBest.get(ctgIdx),mProj1.getFPCData());
	}

	private void fixBES(Block b)
	{
	    // Start by breaking the block into sub-blocks, hopefully
	    // corresponding to inversion breakpoints (but many
	    // sporadic instances also occur). 
	    // First figure out what would be a good gap to break on.
		// Sort the hits  on index 2 so we check gaps on index 1.
		// Larger than normal gaps suggest an inversion breakpoint.
		
		Collections.sort(b.mHits,new SyHitComp());
		Vector<Float> gaps = new Vector<Float>();
		for (int i = 1; i < b.mHits.size(); i++)
		{
			float gap = b.mHits.get(i).mPos1 - b.mHits.get(i-1).mPos1;
			gaps.add(gap);
		}
		SyStats gapStats = new SyStats(gaps);
		float avgGap = gapStats.getAvg();
		float devGap = gapStats.getDev();
		
		float sbGap = avgGap + devGap * mProps.getFloat("subblock_multiple");

		TreeMap<Integer,Vector<SyHit>> subBlocks = new TreeMap<Integer,Vector<SyHit>>();
		subBlocks.put(0, new Vector<SyHit>());
		
		Integer segNum = 0;
		SyHit hPrev = null;
		for (int i = 0; i < b.mHits.size(); i++)
		{
			if (hPrev == null) hPrev = b.mHits.get(i);
			SyHit hCur = b.mHits.get(i);
			if (Math.abs(hCur.mPos1 - hPrev.mPos1) > sbGap)
			{
				segNum++;
				subBlocks.put(segNum, new Vector<SyHit>());
			}
			subBlocks.get(segNum).add(hCur);
			hPrev = hCur;			
		}
		
		// Now go through the sub blocks and adjust the BES based on the correlation
		
		int nSporadic = 0; // number of hits in subblocks too small to work with
		Vector<Double> corrList = new Vector<Double>();
		Vector<Integer> sizeList = new Vector<Integer>();
		for (Vector<SyHit> sbHits : subBlocks.values())
		{
			int size = sbHits.size();
			if (size <= 5)
			{
				nSporadic += size;
				continue;
			}
			double corr = hitCorrelation(sbHits);
			
			corrList.add(corr);
			sizeList.add(size);
			
			// Find and fix the paired BES first. 
			// They are adjusted so that the best-scoring r/f hits agree
			// with the best-scoring block containing that clone.
			
			TreeMap<Integer,TreeMap<RF,SyHit>> bestHits = new TreeMap<Integer,TreeMap<RF,SyHit>>();
			
			// Collect the BES hit locations in this block, for clones
			// not already adjusted using a better block.
			// If a BES hits more than once in the block, we ignore it.
			
			TreeSet<Integer> ignoreClones = new TreeSet<Integer>();
			for (SyHit h : sbHits)
			{
				if (h.mType != SyHitType.Bes) continue;
				if (mClonesFixed2Score.containsKey(h.mCloneIdx1))
				{
					if (mClonesFixed2Score.get(h.mCloneIdx1) > b.mHits.size())
						continue; // already did this clone in a better block
				}
				if (!bestHits.containsKey(h.mCloneIdx1))
					bestHits.put(h.mCloneIdx1, new TreeMap<RF,SyHit>());
				if (!bestHits.get(h.mCloneIdx1).containsKey(h.mRF))
					bestHits.get(h.mCloneIdx1).put(h.mRF, h);
				else 
					ignoreClones.add(h.mCloneIdx1);
			}
			
			// Adjust each paired hit
			// and save them off to use in checking the singles
			
			Vector<SyHit> checks = new Vector<SyHit>();
			
			for (Integer cidx : bestHits.keySet())
			{
				if (bestHits.get(cidx).keySet().size() < 2) continue;
				if (ignoreClones.contains(cidx)) continue;
				
				SyHit hR = bestHits.get(cidx).get(RF.R);
				SyHit hF = bestHits.get(cidx).get(RF.F);
				
				if (!pairAgrees(corr,hR,hF))
				{
					mClonesToSwap.add(cidx);
					int tmp = hR.mPos1;
					hR.mPos1 = hF.mPos1;
					hF.mPos1 = tmp;
				}
				else if (mClonesToSwap.contains(cidx))
					mClonesToSwap.remove(cidx);

				checks.add(hR);
				checks.add(hF);
				mClonesFixed2Score.put(cidx,b.mHits.size());
			}
			
			// Now do the singles, by making them agree
			// with the best-matching paired BES hit or marker hit
			
			for (SyHit h : sbHits)
				if (h.mType == SyHitType.Mrk)
					checks.add(h);
			
			if (checks.size() > 0)
			{
				for (Integer cidx : bestHits.keySet())
				{
					if (bestHits.get(cidx).keySet().size() > 1) continue;
					if (ignoreClones.contains(cidx)) continue;
					if (mClonesSinglesFixed2Score.containsKey(cidx))
					{
						if (mClonesSinglesFixed2Score.get(cidx) > b.mHits.size())
							continue; // already did this clone as a single in a better block
					}
					
					RF rf = bestHits.get(cidx).firstKey();
					SyHit h = bestHits.get(cidx).get(rf);
					
					// Find the marker or paired bes hitting closest on the target
					
					SyHit chBest = null;
					for (SyHit ch : checks)
					{
						if (chBest == null) chBest = ch;
						if (Math.abs(ch.mPos2 - h.mPos2) < Math.abs(chBest.mPos2 - h.mPos2))
							chBest = ch;
					}
					if (!singleAgrees(h,chBest))
						mClonesToSwap.add(cidx);
					else if (mClonesToSwap.contains(cidx))
						mClonesToSwap.remove(cidx);
					mClonesSinglesFixed2Score.put(cidx,b.mHits.size());
				}			
			}
		}
		//mLog.msg("BESFIX gap:" + sbGap + " subblocks:" + subBlocks.size() + " sporadic:" + nSporadic);
		//mLog.msg("correlations:" + Utils.join(corrList, ","));
		//mLog.msg("sizes:" + Utils.join(sizeList, ","));
	}
	
	// Check whether a hit pair agrees with the given correlation
	// (i.e., if corr < 0 the lines should cross, otherwise not)
	private boolean pairAgrees(double corr, SyHit hR, SyHit hF)
	{
		return (0 < corr*(hR.mPos1 - hF.mPos1)*(hR.mPos2 - hF.mPos2));
	}
	
	// See if the given hit would be a better match with BES swapped
	
	private boolean singleAgrees(SyHit h, SyHit check)
	{	
		int diff1 = Math.abs(h.mPos1 - check.mPos1);
		int diff1_alt = Math.abs(h.mPos1_alt - check.mPos1);
		
		return (diff1 < diff1_alt);

	}
	
	private void doCloneSwaps() throws SQLException
	{
		for (int cidx : mClonesToSwap)
		{
			String st = "SELECT bes1,bes2 FROM clones WHERE idx='" + cidx + "'";
			ResultSet rs = pool.executeQuery(st);
			if/*while*/ (rs.next()) // mdb changed 7/1/09
			{
				String bes1 = rs.getString("bes1");
				String bes2 = rs.getString("bes2");
				rs.close();
				
				st = "UPDATE clones SET bes1 = '" + bes2 + "', bes2='" + bes1 + "' WHERE idx='" + cidx + "'";
				pool.executeUpdate(st);
			}
		}
	}
	// Add the mirror reflected blocks for self alignments
	private void symmetrizeBlocks() throws Exception
	{
		Vector<Integer> idxlist = new Vector<Integer>();
		ResultSet rs = pool.executeQuery("select idx from blocks where pair_idx=" + mPairIdx + " and grp1_idx != grp2_idx ");
		while (rs.next())
		{
			idxlist.add(rs.getInt("idx"));
		}
		rs.close();
		for (int idx : idxlist)
		{
			pool.executeUpdate("insert into blocks (select 0,pair_idx,blocknum,proj2_idx,grp2_idx,start2,end2,ctgs2,proj1_idx,grp1_idx,start1,end1,ctgs1," +
					"level,contained,comment,corr,score,0,0,0,0 from blocks where idx=" + idx + ")");
			rs = pool.executeQuery("select max(idx) as maxidx from blocks");
			rs.first();
			int newIdx = rs.getInt("maxidx");
			pool.executeUpdate("insert into pseudo_block_hits (select pseudo_hits.refidx," + newIdx + " from pseudo_block_hits " +
					" join pseudo_hits on pseudo_hits.idx=pseudo_block_hits.hit_idx where block_idx=" + idx + ")");
		}
		assert(mProj1.idx == mProj2.idx);
		Vector<Integer> grps = new Vector<Integer>();
		rs = pool.executeQuery("select idx from groups where proj_idx=" + mProj1.idx);
		while (rs.next())
		{
			grps.add(rs.getInt("idx"));
		}
		for (int gidx : grps)
		{
			// The self-self blocks
			int maxBlkNum = 0;
			rs = pool.executeQuery("select max(blocknum) as bnum from blocks where grp1_idx=" + gidx + " and grp1_idx=grp2_idx");
			rs.first();
			maxBlkNum = rs.getInt("bnum");
			int newMaxBlkNum = 2*maxBlkNum + 1;
			
			// For each old block, make a new block with reflected coordinates, and blocknum computed as in the query
			pool.executeUpdate("insert into blocks (select 0,pair_idx," + newMaxBlkNum + "-blocknum,proj2_idx,grp2_idx,start2,end2,ctgs2,proj1_idx,grp1_idx,start1,end1,ctgs1," +
					"level,contained,comment,corr,score,0,0,0,0 from blocks where grp1_idx=grp2_idx and grp1_idx=" + gidx + " and blocknum <= " + maxBlkNum + ")"); // last clause prob not needed
			
			// Lastly add the reflected block hits. For each original block hit, get its original block, get the new block (known
			// by its blocknum), get the reflected hit (from refidx), and add the reflected hit as a block hit for the new block.
			pool.executeUpdate("insert into pseudo_block_hits (select ph.refidx,b.idx from pseudo_block_hits as pbh " +
					" join blocks as b1 on b1.idx=pbh.block_idx " +
					" join blocks as b on b.grp1_idx=" + gidx + " and b.grp2_idx=" + gidx + " and b.blocknum=" + newMaxBlkNum + "-b1.blocknum " +
					" join pseudo_hits as ph on ph.idx=pbh.hit_idx where b1.blocknum<=" + maxBlkNum +" and ph.grp1_idx=" + gidx + " and ph.grp2_idx=" + gidx + ")");

		}
/*		idxlist = new Vector<Integer>();
		rs = pool.executeQuery("select idx from blocks where pair_idx=" + mPairIdx + " and grp1_idx = grp2_idx ");
		while (rs.next())
		{
			idxlist.add(rs.getInt("idx"));
		}
		rs.close();
		for (int idx : idxlist)
		{
			// First we have to find the next available block number in this grp/grp pair
			int bnum = 0;
			rs = pool.executeQuery("select max(blocknum) as bnum from blocks where grp1_idx=grp2_idx " +
					"and grp1_idx=(select grp1_idx from blocks where idx=" + idx + ")");
			rs.first();
			bnum = 1 + rs.getInt("bnum");
			pool.executeUpdate("insert into blocks (select 0,pair_idx,blocknum,proj2_idx,grp2_idx,start2,end2,ctgs2,proj1_idx,grp1_idx,start1,end1,ctgs1," +
					"level,contained,comment,corr,score,0,0,0,0 from blocks where idx=" + idx + ")");
			rs = pool.executeQuery("select max(idx) as maxidx from blocks");
			rs.first();
			int newIdx = rs.getInt("maxidx");
			pool.executeUpdate("insert into pseudo_block_hits (select pseudo_hits.refidx," + newIdx + " from pseudo_block_hits " +
					" join pseudo_hits on pseudo_hits.idx=pseudo_block_hits.hit_idx where block_idx=" + idx + ")");
		}*/
	}
	private void dumpData() throws Exception
	{
		ResultSet rs = null;
		
		String idField1 = Utils.getProjProp(mProj1.idx, "annot_id_field", pool);
		String idField2 = Utils.getProjProp(mProj2.idx, "annot_id_field", pool);
		if (idField1 == null) idField1="ID";
		if (idField2 == null) idField2="ID";
		
		File resDir = new File(mPairDir,"results");
		if (!resDir.exists())
		{
			resDir.mkdir();	
		}
		File blockFile = new File(resDir,"blocks");
		if (blockFile.exists())
		{
			blockFile.delete();	
		}
		File anchFile = new File(resDir,"anchors");
		if (anchFile.exists())
		{
			anchFile.delete();	
		}	
		FileWriter fw = new FileWriter(blockFile);
		fw.write("grp1\tgrp2\tblock\tstart1\tend1\tstart2\tend2\t#hits\t#gene1\t#gene2\t%gene1\t%gene2\n");
		rs = pool.executeQuery("select blocknum, grp1.name as gname1,grp2.name as gname2, " +
			"start1,end1,start2,end2,score,ngene1,ngene2,genef1,genef2 from blocks join groups as grp1 on grp1.idx=blocks.grp1_idx " + 
			" join groups as grp2 on grp2.idx=blocks.grp2_idx where blocks.pair_idx=" + mPairIdx + 
			" order by grp1.sort_order asc,grp2.sort_order asc,blocknum asc");
		while (rs.next())
		{
			int bidx = rs.getInt("blocknum");
			String grp1 = rs.getString("gname1");	
			String grp2 = rs.getString("gname2");	
			int s1 = rs.getInt("start1");
			int e1 = rs.getInt("end1");
			int s2 = rs.getInt("start2");
			int e2 = rs.getInt("end2");
			int nhits = rs.getInt("score");
			int ng1 = rs.getInt("ngene1");
			int ng2 = rs.getInt("ngene2");
			float gf1 = rs.getFloat("genef1");
			float gf2 = rs.getFloat("genef2");
			gf1 = ((float)((int)(1000*gf1)))/10;
			gf2 = ((float)((int)(1000*gf2)))/10;
			fw.write(grp1 + "\t" + grp2 + "\t" + bidx + "\t" + s1 + "\t" + e1 + "\t" + s2 + "\t" + e2 + "\t" +
					nhits + "\t" + ng1 + "\t" + ng2 + "\t" + gf1 + "\t" + gf2 + "\n");
		}
		fw.flush();
		fw.close();

		fw = new FileWriter(anchFile);
		fw.write("block\tstart1\tend1\tstart2\tend2\tannot1\tannot2\n");
		rs = pool.executeQuery("select blocknum, grp1.name as gname1,grp2.name as gname2, " +
			"ph.start1,ph.end1,ph.start2,ph.end2,a1.name,a2.name from blocks join groups as grp1 on grp1.idx=blocks.grp1_idx " + 
			" join groups as grp2 on grp2.idx=blocks.grp2_idx " +
			" join pseudo_block_hits as pbh on pbh.block_idx=blocks.idx " +
			" join pseudo_hits as ph on ph.idx=pbh.hit_idx " + 
			" left join pseudo_annot as a1 on a1.idx=ph.annot1_idx " +
			" left join pseudo_annot as a2 on a2.idx=ph.annot2_idx " +
			" where blocks.pair_idx=" + mPairIdx + 
			" order by grp1.sort_order asc,grp2.sort_order asc,blocknum asc, ph.start1 asc");
		while (rs.next())
		{
			int bidx = rs.getInt("blocknum");
			String grp1 = rs.getString("gname1");	
			String grp2 = rs.getString("gname2");	
			int s1 = rs.getInt("start1");
			int e1 = rs.getInt("end1");
			int s2 = rs.getInt("start2");
			int e2 = rs.getInt("end2");
			String a1 = rs.getString("a1.name");	
			String a2 = rs.getString("a2.name");
			
			a1 = parseAnnotID(a1,idField1);
			a2 = parseAnnotID(a2,idField2);
			
			fw.write(grp1 + "." + grp2 + "." + bidx + "\t" + s1 + "\t" + e1 + "\t" + s2 + "\t" + e2 +
							"\t" + a1 + "\t" + a2 + "\n");

		}
		fw.flush();
		fw.close();
	}
	private String parseAnnotID(String in, String fieldName)
	{
		if (in != null)
		{
			for (String field : in.split(";"))
			{
				if (field.trim().startsWith(fieldName))
				{
					field = field.substring(1 + fieldName.length()).trim();
					if (field.startsWith("="))
					{
						field = field.substring(2).trim();
					}
					return field;
				}
			}
		}		
		
		return in;
	}
	// If switchProjects is set, then A is being ordered against B, but 
	// the alignment was run, and blocks are stored, as B vs. A. 
	// Hence, we have to reverse the groups we get from the blocks. 
	private void orderGroups(boolean switchProjects) throws Exception
	{
		Project pOrder = (switchProjects ? mProj2 : mProj1);
		Project pTarget = (switchProjects ? mProj1 : mProj2);
	
		File ordFile = new File("data/pseudo/" + pOrder.name + "/anchoring.csv");
		if (ordFile.exists()) ordFile.delete();
		FileWriter ordFileW = new FileWriter(ordFile);
		
		mLog.msg("Ordering " + pOrder.getName() + " contigs against " + pTarget.getName());
		//mLog.msg("(Other alignment pairs involving " + mProj1.getName() + " will need to be reloaded.) ");
		ResultSet rs;
		TreeSet<Integer> alreadyFlipped = new TreeSet<Integer>();
		rs = pool.executeQuery("select idx,flipped from groups where proj_idx=" + pOrder.idx);
		while (rs.next())
		{
			int idx = rs.getInt("idx");
			boolean flipped = rs.getBoolean("flipped");
			if (flipped) alreadyFlipped.add(idx);
		}
		pool.executeUpdate("update groups set flipped=0,sort_order=idx where proj_idx=" + pOrder.idx);
				
		// We're going to order the grp1 by finding the grp2 that they have the most synteny
		// hits with, and taking the start point of those hits.
		TreeSet<Integer> grp1Seen = new TreeSet<Integer>();
		TreeMap<Integer,Integer> grp1score = new TreeMap<Integer,Integer>();
		TreeMap<Integer,Integer> grp1pos = new TreeMap<Integer,Integer>();
		TreeMap<Integer,Integer> grp1len = new TreeMap<Integer,Integer>();
		TreeMap<Integer,String> grp1name = new TreeMap<Integer,String>();
		TreeMap<Integer,String> newGrp = new TreeMap<Integer,String>();
		TreeSet<Integer> grp1flip = new TreeSet<Integer>();
		TreeMap<Integer,Vector<Integer>> grpMaps = new TreeMap<Integer,Vector<Integer>>();
		Vector<Integer> grp2Order = new Vector<Integer>();
		rs = pool.executeQuery("select idx from groups where proj_idx=" + pTarget.getIdx() + " order by sort_order asc");
		while (rs.next())
		{
			grp2Order.add(rs.getInt("idx"));
		}
		// note avg(corr) is necessary because of the grouping, and the grouping is because we don't store the # of hits in the blocks table
		rs = pool.executeQuery("select grp1_idx, grp2_idx, grp2.fullname, grp1.fullname, count(*) as score, start1, start2,avg(corr) as corr, " + 
					" ps1.length as len1, ps2.length as len2  from blocks " + 
					" join pseudo_block_hits on pseudo_block_hits.block_idx=blocks.idx " +
					" join groups as grp2 on grp2.idx=grp2_idx " +
					" join groups as grp1 on grp1.idx=grp1_idx " +
					" join pseudos as ps1 on ps1.grp_idx = grp1_idx " +
					" join pseudos as ps2 on ps2.grp_idx = grp2_idx " +
					" where proj1_idx=" + mProj1.getIdx() + " and proj2_idx=" + mProj2.getIdx() + 
					" group by blocks.grp1_idx,blocks.grp2_idx,blocks.blocknum order by score desc" );
		while (rs.next())
		{
			int grp1Idx = (switchProjects ? rs.getInt("grp2_idx") : rs.getInt("grp1_idx"));
			int grp2Idx = (switchProjects ? rs.getInt("grp1_idx") : rs.getInt("grp2_idx"));
			int pos = (switchProjects ? rs.getInt("start1") : rs.getInt("start2"));
			int len1 = (switchProjects ? rs.getInt("len2") : rs.getInt("len1"));
			int score = rs.getInt("score");
			float corr = rs.getFloat("corr");
			String grp2Name = (switchProjects ? rs.getString("grp1.fullname") : rs.getString("grp2.fullname"));
			String grp1Name = (switchProjects ? rs.getString("grp2.fullname") : rs.getString("grp1.fullname"));
			if (grp1Seen.contains(grp1Idx))
			{
				// If we've seen this group before then we've already got its best block
				continue;	
			}
			if (!grpMaps.containsKey(grp2Idx))
			{
				grpMaps.put(grp2Idx, new Vector<Integer>());	
			}
			grpMaps.get(grp2Idx).add(grp1Idx);
			grp1Seen.add(grp1Idx);
			grp1pos.put(grp1Idx, pos);
			grp1len.put(grp1Idx, len1);
			grp1score.put(grp1Idx,score);
			grp1name.put(grp1Idx,grp1Name);
			newGrp.put(grp1Idx, grp2Name);
			if (corr < 0) grp1flip.add(grp1Idx);
		}
		rs.close();
		// It seems like there should be an easier way to do this...
		int curOrd = 1;
		TreeMap<Integer,Integer> grp2ord = new TreeMap<Integer,Integer>();
		Vector<Integer> grp1_ordered = new Vector<Integer>();
		for (int grp2Idx : grp2Order)
		{
			if (!grpMaps.containsKey(grp2Idx)) continue;
			Integer[] grp1s = grpMaps.get(grp2Idx).toArray(new Integer[1]);
			Integer[] pos = new Integer[grp1s.length];
			for (int i = 0; i < grp1s.length; i++)
			{
				pos[i] = grp1pos.get(grp1s[i]);
				//System.out.println("grp: " + grp1s[i] + " pos:" + pos[i]);
			}
			// We have parallel arrays of grp and pos, so sort the pos and apply this sort to the grp
			Utils.HeapDoubleSort(pos, grp1s, new Utils.ObjCmp());
			for (int i = 0; i < grp1s.length; i++)
			{
				grp2ord.put(grp1s[i], curOrd);
				grp1_ordered.add(grp1s[i]);
				curOrd++;
			}
		}
		
		String ordPfx = Utils.getProjProp(pOrder.idx, "grp_prefix", pool);
		String targPfx = Utils.getProjProp(pTarget.idx, "grp_prefix", pool);

		// assign the ones that didn't align anywhere
		Vector<Integer> allGrpIdx = new Vector<Integer>();
		rs = pool.executeQuery("select idx from groups where proj_idx=" + pOrder.idx);
		while (rs.next())
		{
			int idx = rs.getInt("idx");
			allGrpIdx.add(idx);
			if (!grp2ord.containsKey(idx))
			{
				grp2ord.put(idx, curOrd);
				curOrd++;
			}	
		}
		ordFileW.write(pOrder.name + "-" + ordPfx + "," + pTarget.name + "-" + targPfx + ",pos,F/R,#anchors," + pOrder.name + "-length\n");
		
		for (int idx : grp1_ordered)//grp2ord.keySet())
		{
			int ord = grp2ord.get(idx);
			int pos = grp1pos.get(idx);
			int len = grp1len.get(idx);
			String RC = "F";
			if (grp1flip.contains(idx))
			{
				pool.executeUpdate("update groups set sort_order=" + ord + ",flipped=1 where idx=" + idx);
				RC = "R";
			}
			else
			{
				pool.executeUpdate("update groups set sort_order=" + ord + ",flipped=0 where idx=" + idx);
			}
			ordFileW.write(grp1name.get(idx) + "," + newGrp.get(idx) + "," + pos + "," + RC + "," + grp1score.get(idx) + "," + len + "\n"); 

		}
		ordFileW.close();
		// Now, update the newly flipped/unflipped groups and the anchors and synteny blocks containing them
		mLog.msg("Updating flipped contigs and anchors....");
		for (int idx : allGrpIdx)
		{
			if ( (grp1flip.contains(idx) && !alreadyFlipped.contains(idx)) || 
					(!grp1flip.contains(idx) && alreadyFlipped.contains(idx)))
			{
				// First, reverse-conjugate the group sequence
				String seq = "";
				rs = pool.executeQuery("select seq from pseudo_seq2 where grp_idx=" + idx + " order by chunk asc");
				while (rs.next())
				{
					seq += rs.getString("seq");
				}
				rs.close();
				pool.executeUpdate("delete from pseudo_seq2 where grp_idx=" + idx);
				String revSeq = Utils.reverseComplement(seq);
				// Finally, upload the sequence in chunks
				for (int chunk = 0; chunk*CHUNK_SIZE < revSeq.length(); chunk++)
				{
					int start = chunk*CHUNK_SIZE;
					int len = Math.min(CHUNK_SIZE, seq.length() - start );
					//System.out.println("chunk " + chunk + " start " + start + " len " + len);
					String cseq = revSeq.substring(start,start + len );
					String st = "INSERT INTO pseudo_seq2 VALUES('" + idx + "','" + chunk + "','" + cseq + "')";
					pool.executeUpdate(st);
				}
				// Now, flip coordinates of all anchors and blocks containing this group
				pool.executeUpdate("update pseudo_hits as ph, pseudos as p  " +
						" set ph.start1=p.length-ph.end1, ph.end1=p.length-ph.start1 " +
						"where ph.grp1_idx=" + idx + " and p.grp_idx=" + idx );
				pool.executeUpdate("update pseudo_hits as ph, pseudos as p  " +
						" set ph.start2=p.length-ph.end2, ph.end2=p.length-ph.start2 " +
						"where ph.grp2_idx=" + idx + " and p.grp_idx=" + idx );
				pool.executeUpdate("update bes_hits as h, pseudos as p  " +
						" set h.start2=p.length-h.end2, h.end2=p.length-h.start2 " +
						"where h.grp2_idx=" + idx + " and p.grp_idx=" + idx );
				pool.executeUpdate("update mrk_hits as h, pseudos as p  " +
						" set h.start2=p.length-h.end2, h.end2=p.length-h.start2 " +
						"where h.grp2_idx=" + idx + " and p.grp_idx=" + idx );
				
				pool.executeUpdate("update blocks as b, pseudos as p  " +
						" set b.start1=p.length-b.end1, b.end1=p.length-b.start1, b.corr=-b.corr " +
						"where b.grp1_idx=" + idx + " and p.grp_idx=" + idx );
				pool.executeUpdate("update blocks as b, pseudos as p  " +
						" set b.start2=p.length-b.end2, b.end2=p.length-b.start2, b.corr=-b.corr " +
						"where b.grp2_idx=" + idx + " and p.grp_idx=" + idx );

			}
			
		}
		// Lastly, print out the two ordered projects
		mLog.msg("Creating anchored project from " + pOrder.getName());
		mLog.msg("Wrote " + ordFile.getAbsolutePath());
		String orderedName0 = pOrder.getName() + "_ordered";
		String groupedName0 = pOrder.getName() + "_anchored";
		
		String orderedName = orderedName0;
		String groupedName = groupedName0;
		
		groupedName = groupedName0; 
		File groupedDir = new File("data/pseudo/" + groupedName);
		if (groupedDir.exists())
		{
			int ret = JOptionPane.showConfirmDialog(null, "Anchored project " + groupedName + " was already created and " +
					" will be overwritten; Continue? ","Confirm",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);	
			if (ret == JOptionPane.YES_OPTION)
			{
				;
			}
			else 
			{
				return;	
			}
			Utils.clearDir(groupedDir);
			groupedDir.delete();

		}	
		groupedDir.mkdir();
		
		File groupedSeq = new File(groupedDir,"sequence"); groupedSeq.mkdir();
		File groupedPs = new File(groupedSeq,"pseudo"); groupedPs.mkdir();
		File groupedAndir = new File(groupedDir,"annotation"); groupedAndir.mkdir();
		
		File groupedFasta = new File(groupedPs,groupedName + ".fasta");

		File groupedGFF = new File(groupedAndir,groupedName + ".gff");
		
		File grpParam = new File(groupedDir,"params");

		String separator = "NNNNNNNNNN" + "NNNNNNNNNN" +"NNNNNNNNNN" +"NNNNNNNNNN" +"NNNNNNNNNN" +
						"NNNNNNNNNN" +"NNNNNNNNNN" +"NNNNNNNNNN" +"NNNNNNNNNN" +"NNNNNNNNNN";
		
		
		FileWriter grpFastaW = new FileWriter(groupedFasta);
		
		FileWriter grpParamW = new FileWriter(grpParam);
		
		FileWriter groupedGFFW = new FileWriter(groupedGFF);
		
		Vector<String> grpOrder = new Vector<String>();
		rs = pool.executeQuery("select groups.fullname, groups.idx, groups.flipped, pseudo_seq2.seq from groups join pseudo_seq2 " +  
				" on pseudo_seq2.grp_idx=groups.idx where groups.proj_idx=" + pOrder.idx + 
				" order by groups.sort_order asc, pseudo_seq2.chunk asc");
		int curGrpIdx = -1;
		String curNewGrp = "";
		String curNewGrpName = "";
		String curGrpName = "";
		String prevGrpName = "";
		int groupedPos = 0;
		boolean unanchStarted = false;
		while (rs.next())
		{
			String name = rs.getString("fullname");
			int grpIdx = rs.getInt("idx");
			String seq = rs.getString("seq");
			boolean flipped = rs.getBoolean("flipped");
			if (grpIdx != curGrpIdx)
			{
				prevGrpName = curGrpName;
				curGrpIdx = grpIdx;
				curGrpName = name;
				if (newGrp.containsKey(curGrpIdx))
				{
					if (!newGrp.get(curGrpIdx).equals(curNewGrp))
					{
						curNewGrp = newGrp.get(curGrpIdx);
						curNewGrpName = curNewGrp;
						grpOrder.add(curNewGrpName);
						grpFastaW.write(">" + curNewGrpName + "\n");
						groupedPos = 0;
					}
					else
					{
						// still in the same anchor section, hence must have written a prior contig to it, hence 
						// need a separator
						grpFastaW.write(separator); grpFastaW.write("\n");	
						groupedGFFW.write(curNewGrpName + "\tconsensus\tgap\t" + groupedPos + "\t" + (groupedPos+separator.length()) + 
								"\t.\t+\t.\tName \"GAP:" + prevGrpName + "-" + curGrpName + "\"\n");
						groupedPos += separator.length();
					}
				}
				else
				{
					// we're at the unanchored section
					if (!unanchStarted)
					{
						curNewGrpName = targPfx + "UNK";
						grpOrder.add(curNewGrpName);
						grpFastaW.write(">" + curNewGrpName + "  |  " + curGrpName + "\n");
						groupedPos = 0;	
						unanchStarted = true;
					}
					else
					{
						grpFastaW.write(separator);	 grpFastaW.write("\n");
						groupedGFFW.write(curNewGrpName + "\tconsensus\tgap\t" + groupedPos + "\t" + (groupedPos+separator.length()) + 
								"\t.\t+\t.\tName \"GAP:" + prevGrpName + "-" + curGrpName + "\"\n");
						groupedPos += separator.length();
					}
				}
			}
			for (int i = 0; i < seq.length(); i += 50)
			{
				int end = Math.min(i+50,seq.length()-1);
				String line = seq.substring(i, end);
				//ordFastaW.write(line); ordFastaW.write("\n");
				grpFastaW.write(line); grpFastaW.write("\n");
			}
			groupedPos += seq.length();
		}
		grpFastaW.close();
		groupedGFFW.close();
		String dispName;

		dispName = pOrder.displayName + " - Anchored";
		grpParamW.write("category = " + pOrder.category + "\ndisplay_name=" + dispName + "\n");
		grpParamW.write("grp_prefix=" + pTarget.grpPrefix + "\n");
		grpParamW.close();

		mLog.msg("Created anchored project " + groupedName);
	}
	
	private void setGeneRunCounts() throws Exception
	{
		mLog.msg("Label adjacent gene anchors");
		if (!pool.tableHasColumn("pseudo_hits", "runsize"))
		{
			pool.executeUpdate("alter table pseudo_hits add runsize int default 0");
		}
		pool.executeUpdate("update pseudo_hits set runsize=0 where pair_idx=" + mPairIdx);
		if (!pool.tableHasColumn("pseudo_annot", "genenum"))
		{
			pool.executeUpdate("alter table pseudo_annot add genenum int default 0");
		}
		// First set the order number for the genes, using the same number for 
		// ones which overlap
		pool.executeUpdate("update pseudo_annot, groups set pseudo_annot.genenum=0 where pseudo_annot.grp_idx=groups.idx and groups.proj_idx=" + mProj1.idx);
		pool.executeUpdate("update pseudo_annot, groups set pseudo_annot.genenum=0 where pseudo_annot.grp_idx=groups.idx and groups.proj_idx=" + mProj2.idx);
		for (Project p : new Project[]{mProj1, mProj2})
		{
			for (Group g : p.getGroups())
			{
				int genenum = 0;
				PreparedStatement ps = pool.prepareStatement("update pseudo_annot set genenum=? where idx=?");
				ResultSet rs = pool.executeQuery("select idx,start,end from pseudo_annot where grp_idx=" + g.idx + " and type='gene' order by start asc");
				int sprev=-1, eprev=-1;
				while (rs.next())
				{
					int idx = rs.getInt("idx");
					int s = rs.getInt("start");
					int e = rs.getInt("end");
					
					if (sprev == -1 || !Utils.intervalsOverlap(sprev, eprev, s, e, 0))
					{
						genenum++;
					}
					ps.setInt(1, genenum);
					ps.setInt(2, idx);
					ps.addBatch();
					sprev=s; eprev=e;
				}
				ps.executeBatch();
			}
		}
		int num2go = mProj1.getGroups().size()*mProj2.getGroups().size();
		System.out.println(num2go + " to pairs analyze");
		// Now get the hits with genes ordered on one side and just count the ordered runs on the other side
		for (Group g1 : mProj1.getGroups())
		{
			for (Group g2: mProj2.getGroups())
			{
				//if (!g1.name.equals("3") || !g2.name.equals("1")) continue;
				//System.out.println("groups " + g1.idx + "," + g2.idx);
				//
				// Build sparse matrix of gene number hits; complex because of how stored in DB and
				// because a hit can hit more than one gene number.
				// We will be strict about connecting + strand hits in forward direction, - strand in reverse direction.
				// 
				ResultSet rs = pool.executeQuery("select max(genenum) as maxnum from pseudo_annot as pa where pa.grp_idx=" + g2.idx);
				rs.first();
				int maxN2 = rs.getInt("maxnum");
				
				rs = pool.executeQuery("select h.idx,a.genenum,a.grp_idx, h.strand from pseudo_hits as h " +
						" join pseudo_hits_annot as pha on pha.hit_idx=h.idx " +
						" join pseudo_annot as a on a.idx=pha.annot_idx " +
						//" join pseudo_block_hits as pbh on pbh.hit_idx=h.idx  " +
						//" join blocks as b on b.idx=pbh.block_idx and b.blocknum=1 " +
						" where h.grp1_idx=" + g1.idx + " and h.grp2_idx=" + g2.idx +
						//" and h.start1 > 3141000 and h.end1 < 3176000 " + 
						" order by a.genenum asc, h.idx asc");
				TreeMap<Integer,Vector<Integer>> hidx2gn1 = new TreeMap<Integer,Vector<Integer>>();
				TreeMap<Integer,Vector<Integer>> hidx2gn2 = new TreeMap<Integer,Vector<Integer>>();
				TreeMap<Integer,Boolean> hidx2inv = new TreeMap<Integer,Boolean>();
				TreeMap<Integer,TreeMap<Integer,Integer>> gn2hidxF = new TreeMap<Integer,TreeMap<Integer,Integer>>();
				TreeMap<Integer,TreeMap<Integer,Integer>> gn2hidxR = new TreeMap<Integer,TreeMap<Integer,Integer>>();
				while (rs.next())
				{
					int hidx = rs.getInt("idx");
					int gnum = rs.getInt("genenum");
					int grp = rs.getInt("grp_idx");
					String str = rs.getString("strand");
					boolean inv = (str.contains("+") && str.contains("-"));
					if (inv && grp == g2.idx)
					{
						// reverse the gene numbers for inverted hits since we want to connect them backwards
						// use 2* so they won't overlap (although they will also go in separate sparse mats)
						gnum = (1 + 2*maxN2) - gnum;
					}
					if (!hidx2gn1.containsKey(hidx))
					{
						hidx2gn1.put(hidx, new Vector<Integer>());
						hidx2gn2.put(hidx, new Vector<Integer>());
						hidx2inv.put(hidx, inv);			}
					if (grp == g1.idx)
					{
						hidx2gn1.get(hidx).add(gnum);
					}
					else
					{
						hidx2gn2.get(hidx).add(gnum);
					}
				}
				//System.out.println(hidx2gn1.size() + " hits");
				for (int hidx : hidx2gn1.keySet())
				{
					int n1 = hidx2gn1.get(hidx).size();
					int n2 = hidx2gn2.get(hidx).size();
					//System.out.println("hit " + hidx + " n1 " + n1 + " n2 " + n2);
					if (n1 == 0 || n2 == 0) continue;
					TreeMap<Integer,TreeMap<Integer,Integer>> sparse = gn2hidxF;
					if (hidx2inv.get(hidx))
					{
						sparse = gn2hidxR;
					}
					for (int i = 0; i < Math.min(n1,n2); i++)
					{
						int gn1 = hidx2gn1.get(hidx).get(i);
						int gn2 = hidx2gn2.get(hidx).get(i);
						if (!sparse.containsKey(gn1))
						{
							sparse.put(gn1, new TreeMap<Integer,Integer>());
							sparse.get(gn1).put(gn2, hidx);
						}
					}
					for (int i = Math.min(n1,n2); i < Math.max(n1, n2); i++)
					{
						int gn1 = (i < n1 ? hidx2gn1.get(hidx).get(i) : hidx2gn1.get(hidx).get(n1-1) );
						int gn2 = (i < n2 ? hidx2gn2.get(hidx).get(i) : hidx2gn2.get(hidx).get(n2-1) );
						if (!sparse.containsKey(gn1))
						{
							sparse.put(gn1, new TreeMap<Integer,Integer>());
							sparse.get(gn1).put(gn2, hidx);
						}
					}
					
				}
				//System.out.println("Sparse matrix traverse...");
				// Connect the dots in the sparse matrices.
				// Both use increasing gene order since we already reversed the gene order if necessary.
				TreeMap<Integer,Integer> hitScores = new TreeMap<Integer,Integer>();
				int gapSize = 1;
				for (Object o : new Object[]{gn2hidxF,gn2hidxR})
				{
					TreeMap<Integer,TreeMap<Integer,Integer>> sparse = (TreeMap<Integer,TreeMap<Integer,Integer>>)o;
					TreeMap<Integer,TreeMap<Integer,Integer>> scores = new TreeMap<Integer,TreeMap<Integer,Integer>>();
					TreeMap<Integer,TreeSet<Integer>> maxs = new TreeMap<Integer,TreeSet<Integer>>();
					TreeMap<Integer,Integer> backLinks = new TreeMap<Integer,Integer>();
					// Traverse sparse matrix (gn1,gn2) in increasing order of gn1,gn2 (note keySet from tree is sorted)
					// Build backlinks and scores, and track the sparse nodes that are currently heads of maximal chains
					for (int gn1 : sparse.keySet())
					{
						scores.put(gn1, new TreeMap<Integer,Integer>());
						for (int gn2 : sparse.get(gn1).keySet())
						{
							//System.out.println("gn1 " + gn1 + ", gn2 " + gn2);
							scores.get(gn1).put(gn2, 1);
							int gn1_min = gn1 - gapSize;
							int gn2_min = gn2 - gapSize;
							int gn1_connect = -1;
							int gn2_connect = -1;
							int newScore = -1;
							for (int gn1_try = gn1-1; gn1_try >= gn1_min; gn1_try--)
							{
								if (!scores.containsKey(gn1_try)) continue;
								for (int gn2_try = gn2-1; gn2_try >= gn2_min; gn2_try--)
								{
									if (scores.get(gn1_try).containsKey(gn2_try))
									{
										if (scores.get(gn1_try).get(gn2_try)+1 > newScore)
										{
											newScore = scores.get(gn1_try).get(gn2_try)+1;
											gn1_connect = gn1_try;
											gn2_connect = gn2_try;
										}
									}
								}								
							}
							if (newScore > 0) // there was a backlink
							{
								assert(newScore >= 2);
								scores.get(gn1).put(gn2,newScore);
								// If the backlink was to a prior maximal chain, then it's no longer maximal
								if (maxs.containsKey(gn1_connect))
								{
									if (maxs.get(gn1_connect).contains(gn2_connect))
									{
										maxs.get(gn1_connect).remove(gn2_connect);
									}
								}
								if (!maxs.containsKey(gn1))
								{
									maxs.put(gn1, new TreeSet<Integer>());
								}
								maxs.get(gn1).add(gn2); // it is currently maximal just because of the traverse order
								int hidx1 = sparse.get(gn1).get(gn2);
								int hidx2 = sparse.get(gn1_connect).get(gn2_connect);
								if (hidx1 != hidx2) // hit could span adjacent genes...not likely
								{
									//if (!backLinks.containsKey(hidx1) )
									//{
										backLinks.put(hidx1,hidx2);
										//System.out.println("backlink: " + hidx1 + "," + hidx2 + "," + gn1 + "," + gn2);
								
									//}
								}
							}
						}
					}
					// Takes some effort to get a highest-to-lowest ordered list of scores with their corresponding hits
					TreeMap<Integer,Integer> hit2max = new TreeMap<Integer,Integer>(); 
					for (int gn1 : maxs.keySet())
					{
						for (int gn2 : maxs.get(gn1))
						{
							int score = scores.get(gn1).get(gn2);
							int hidx = sparse.get(gn1).get(gn2);
							if (!hit2max.containsKey(hidx) || score > hit2max.get(hidx)) // 2nd should probably never happen
							{
								hit2max.put(hidx, score);
							}
						}
					}
					TreeMap<Integer, TreeSet<Integer>> scores2Hits = new TreeMap<Integer, TreeSet<Integer>>();
					for (int hidx : hit2max.keySet())
					{
						int score = hit2max.get(hidx);
						if (!scores2Hits.containsKey(score))
						{
							scores2Hits.put(score, new TreeSet<Integer>());
						}
						scores2Hits.get(score).add(hidx);
					}

					for (int score : scores2Hits.descendingKeySet())
					{
						for (int hidx : scores2Hits.get(score))
						{
							//System.out.println("score:" + score + " hit:" + hidx);
							if (hitScores.containsKey(hidx)) continue;
							hitScores.put(hidx,score);
							while (backLinks.containsKey(hidx))
							{
								int hidx1 = backLinks.get(hidx);
								//System.out.println("traceback: " + hidx + " ==> " + hidx1);
								hidx = hidx1;
								if (!hitScores.containsKey(hidx))
								{
									hitScores.put(hidx, score);
								}

							}							
						}
					}

				} // end of forward/reverse loop
				PreparedStatement ps = pool.prepareStatement("update pseudo_hits set runsize=? where idx=?");
				for (int hidx : hitScores.keySet())
				{
					int score = hitScores.get(hidx);
					ps.setInt(1, score);
					ps.setInt(2,hidx);
					ps.addBatch();
				}
				ps.executeBatch();
				
				num2go--;
				if (num2go % 200 == 0)
				{
					System.out.println(num2go + "");
				}
			}

		}
	}
	/*
	 *  CLASSES
	 */
	private static class SyHitComp implements Comparator<SyHit> 
    {
        public int compare(SyHit h1, SyHit h2)
        {
        	if (h1.mPos2 != h2.mPos2)
                return h1.mPos2 - h2.mPos2;
        	else
        		return h1.mPos1 - h2.mPos1;
        }
    }
	private static class ctgLocComp implements Comparator<CtgHit> 
    {
        public int compare(CtgHit h1, CtgHit h2)
        {
        	int pos1 = (h1.mS2 + h1.mE2)/2;
        	int pos2 = (h2.mS2 + h2.mE2)/2;
        	
        	return pos1 - pos2;
        }
    }
}

