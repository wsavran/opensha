package scratch.UCERF3.inversion;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.dom4j.DocumentException;
import org.opensha.commons.eq.MagUtils;
import org.opensha.commons.exceptions.GMT_MapException;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.AveSlipModule;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;

import scratch.UCERF3.U3CompoundFaultSystemSolution;
import scratch.UCERF3.U3FaultSystemSolutionFetcher;
import scratch.UCERF3.enumTreeBranches.DeformationModels;
import scratch.UCERF3.enumTreeBranches.FaultModels;
import scratch.UCERF3.enumTreeBranches.InversionModels;
import scratch.UCERF3.enumTreeBranches.ScalingRelationships;
import scratch.UCERF3.enumTreeBranches.SlipAlongRuptureModels;
import scratch.UCERF3.enumTreeBranches.SpatialSeisPDF;
import scratch.UCERF3.inversion.laughTest.UCERF3PlausibilityConfig;
import scratch.UCERF3.logicTree.U3LogicTreeBranch;
import scratch.UCERF3.utils.U3FaultSystemIO;
import scratch.UCERF3.utils.paleoRateConstraints.U3PaleoRateConstraint;
import scratch.UCERF3.utils.paleoRateConstraints.UCERF2_PaleoRateConstraintFetcher;

public class UCERF2_ComparisonSolutionFetcher {
	
//	private static Map<FaultModels, SimpleFaultSystemSolution> cache = Maps.newHashMap();
	private static Table<FaultModels, SlipAlongRuptureModels,
			FaultSystemSolution> cache= HashBasedTable.create();
	
	/**
	 * This creates a UCERF2 reference solution for the given Fault Model. It uses
	 * Magnitudes from UCERF2 where available (when no mapping exists for a rupture, the Average
	 * UCERF2 MA is used which should be almost identical), rates form UCERF2, Tapered Dsr model
	 * and a constant subseismogenic thickness moment reduction (which is only relevant if you anneal
	 * this model, not if you just use the UCERF2 reference solution).
	 * 
	 * @param fm
	 * @return
	 */
	public static FaultSystemSolution getUCERF2Solution(FaultModels fm) {
		return getUCERF2Solution(fm, SlipAlongRuptureModels.TAPERED);
	}
	
	public static FaultSystemSolution getUCERF2Solution(FaultModels fm, SlipAlongRuptureModels slipModel) {
		FaultSystemSolution sol = cache.get(fm, slipModel);
		if (sol == null) {
			DeformationModels dm;
			if (fm == FaultModels.FM2_1)
				dm = DeformationModels.UCERF2_ALL;
			else
				dm = DeformationModels.GEOLOGIC;
			InversionFaultSystemRupSet rupSet = InversionFaultSystemRupSetFactory.forBranch(
					UCERF3PlausibilityConfig.getDefault(), 0, fm, dm, ScalingRelationships.AVE_UCERF2,
					slipModel, InversionModels.CHAR_CONSTRAINED, SpatialSeisPDF.UCERF2);

			sol = getUCERF2Solution(rupSet);
			
			cache.put(fm, slipModel, sol);
		}
		return sol;
	}
	
	public static FaultSystemSolution getUCERF2Solution(InversionFaultSystemRupSet rupSet) {
		return getUCERF2Solution(rupSet, rupSet.getModule(U3LogicTreeBranch.class).getValue(FaultModels.class),
				rupSet.getModule(AveSlipModule.class));
	}
	
	public static FaultSystemSolution getUCERF2Solution(FaultSystemRupSet rupSet, FaultModels fm, AveSlipModule aveSlipModule) {
		ArrayList<double[]> ucerf2_magsAndRates = UCERF3InversionConfiguration.getUCERF2MagsAndrates(rupSet, fm);
		
		double[] mags = new double[ucerf2_magsAndRates.size()];
		double[] rates = new double[ucerf2_magsAndRates.size()];
//		double[] aveSlips = Arrays.copyOf(rupSet.getAveSlipForAllRups(), rupSet.getNumRuptures());
		double[] aveSlips = new double[rupSet.getNumRuptures()];
		for (int i=0; i<ucerf2_magsAndRates.size(); i++) {
			double[] ucerf2_vals = ucerf2_magsAndRates.get(i);
			if (ucerf2_vals == null) {
				mags[i] = rupSet.getMagForRup(i);
				rates[i] = 0;
			} else {
				mags[i] = ucerf2_vals[0];
				rates[i] = ucerf2_vals[1];
				// Dr = Dr * Mo(M)/Mo(M(A))
				// to account for assumptions made in Dr calculation (which uses mag from
				// area and not the actual UCERF2 mag
				aveSlips[i] = aveSlipModule.getAveSlip(i) * MagUtils.magToMoment(mags[i])/
						MagUtils.magToMoment(rupSet.getMagForRup(i));
			}
		}

//		InversionFaultSystemRupSet modRupSet = new InversionFaultSystemRupSet(rupSet, rupSet.getLogicTreeBranch(),
//				rupSet.getOldPlausibilityConfiguration(), aveSlips, rupSet.getCloseSectionsListList(),
//				rupSet.getRupturesForClusters(), rupSet.getSectionsForClusters());
		FaultSystemRupSet modRupSet = new FaultSystemRupSet(rupSet.getFaultSectionDataList(),
				rupSet.getSectionIndicesForAllRups(), mags, rupSet.getAveRakeForAllRups(), rupSet.getAreaForAllRups(),
				rupSet.getLengthForAllRups());

		return new FaultSystemSolution(modRupSet, rates);
	}
	
//	public static void writeUCREF2_CompoundSol(File file, FaultModels fm) throws IOException {
//		FaultSystemSolution tapered = getUCERF2Solution(fm, SlipAlongRuptureModels.TAPERED);
//		FaultSystemSolution uniform = getUCERF2Solution(fm, SlipAlongRuptureModels.UNIFORM);
//		
//		final Map<LogicTreeBranch, InversionFaultSystemSolution> solMap = Maps.newHashMap();
//		solMap.put(tapered.getLogicTreeBranch(), tapered);
//		solMap.put(uniform.getLogicTreeBranch(), uniform);
//		
//		FaultSystemSolutionFetcher fetch = new FaultSystemSolutionFetcher() {
//			
//			@Override
//			public Collection<LogicTreeBranch> getBranches() {
//				return solMap.keySet();
//			}
//			
//			@Override
//			protected InversionFaultSystemSolution fetchSolution(LogicTreeBranch branch) {
//				return solMap.get(branch);
//			}
//		};
//		
//		CompoundFaultSystemSolution.toZipFile(file, fetch);
//	}
	
	public static void main(String[] args) throws GMT_MapException, RuntimeException, IOException, DocumentException {
		FaultModels fm = FaultModels.FM2_1;
		String prefix = "FM2_1_UCERF2_DsrTap_CharConst_COMPARE";
		File dir = new File("/tmp");
		
//		FaultSystemSolution sol = getUCERF2Solution(fm, SlipAlongRuptureModels.TAPERED);
////		BatchPlotGen.makeMapPlots(sol, dir, prefix);
//		FaultSystemIO.writeSol(sol, new File(dir, prefix+"_sol.zip"));
		
//		writeUCREF2_CompoundSol(new File(dir, "UCERF2_MAPPED_COMPOUND_SOL.zip"), FaultModels.FM2_1);
		
//		ArrayList<PaleoRateConstraint> paleoConstraints = UCERF2_PaleoRateConstraintFetcher.getConstraints(sol.getFaultSectionDataList());
//		CommandLineInversionRunner.writePaleoPlots(paleoConstraints, null, sol, dir, prefix);
		
//		SimpleFaultSystemSolution sol = getUCERF2Solution(FaultModels.FM3_1);
//		BatchPlotGen.makeMapPlots(sol, new File("/tmp"), "ucerf2_fm3_compare");
	}

}
