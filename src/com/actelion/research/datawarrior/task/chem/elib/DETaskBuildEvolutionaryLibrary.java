/*
 * Copyright 2017 Idorsia Pharmaceuticals Ltd., Hegenheimermattweg 91, CH-4123 Allschwil, Switzerland
 *
 * This file is part of DataWarrior.
 * 
 * DataWarrior is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * DataWarrior is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with DataWarrior.
 * If not, see http://www.gnu.org/licenses/.
 *
 * @author Thomas Sander
 */

package com.actelion.research.datawarrior.task.chem.elib;

import com.actelion.research.chem.*;
import com.actelion.research.datawarrior.DEFrame;
import com.actelion.research.datawarrior.DataWarrior;
import com.actelion.research.datawarrior.task.AbstractTask;
import com.actelion.research.datawarrior.task.TaskUIDelegate;
import com.actelion.research.gui.JProgressPanel;
import com.actelion.research.gui.JStructureView;
import com.actelion.research.gui.dock.ShadowBorder;
import com.actelion.research.gui.hidpi.HiDPIHelper;
import com.actelion.research.table.model.CompoundTableEvent;
import com.actelion.research.table.model.CompoundTableListHandler;
import com.actelion.research.table.model.CompoundTableModel;
import com.actelion.research.table.view.JVisualization;
import com.actelion.research.table.view.JVisualization2D;
import com.actelion.research.table.view.VisualizationPanel2D;
import com.actelion.research.util.DoubleFormat;
import com.actelion.research.util.concurrent.AtomicFloat;
import info.clearthought.layout.TableLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.dnd.DnDConstants;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

public class DETaskBuildEvolutionaryLibrary extends AbstractTask implements ActionListener,Runnable,TaskConstantsELib {
	public static final String TASK_NAME = "Build Evolutionary Library";

	private static final int VIEW_BACKGROUND = 0xFF081068;
	private static final int AUTOMATIC_HISTORIC_GENERATIONS = 32;
	private static final String THREAD_NAME_PREFIX = "MT";

	private Frame				mParentFrame;
	private DataWarrior			mApplication;
	private CompoundTableModel	mSourceTableModel;
	private JDialog				mControllingDialog;
	private DEFrame				mTargetFrame;
	private JStructureView[]	mCompoundView;
	private ShadowBorder[]      mCompoundViewBorder;
	private JLabel[]			mFitnessLabel;
	private JLabel				mLabelGeneration,mLabelRun;
	private JProgressPanel		mProgressPanel;
	private FitnessEvolutionPanel mFitnessEvolutionPanel;
	private volatile boolean	mKeepData,mStopProcessing;
	private AtomicFloat			mBestFitness;
	private AtomicInteger		mCurrentResultID;
	private volatile ArrayBlockingQueue<MutationQueueEntry> mMutationQueue;

	public DETaskBuildEvolutionaryLibrary(DEFrame owner, DataWarrior application) {
		super(owner, false);

		mParentFrame = owner;
		mSourceTableModel = owner.getTableModel();
		mApplication = application;
		}

	@Override
	public String getTaskName() {
		return TASK_NAME;
		}

	@Override
	public String getHelpURL() {
		return "/html/help/chemistry.html#EvolutionaryLibraries";
		}

	@Override
	public TaskUIDelegate createUIDelegate() {
		return new UIDelegateELib(mParentFrame, mSourceTableModel);
		}

	@Override
	public DEFrame getNewFrontFrame() {
		return mTargetFrame;
		}

	private JDialog createControllingDialog() {
		JPanel p2 = new JPanel();
		final int scaled4 = HiDPIHelper.scale(4);
		final int scaled8 = HiDPIHelper.scale(8);
		final int scaled120 = HiDPIHelper.scale(120);
		final int scaled160 = HiDPIHelper.scale(160);
		double[][] size2 = { {scaled8, scaled160, scaled8, scaled160, scaled8, scaled160, scaled8, scaled160, scaled8},
							 {scaled8, TableLayout.PREFERRED, scaled4, scaled120, TableLayout.PREFERRED, 2*scaled8, TableLayout.PREFERRED, scaled4, scaled120, TableLayout.PREFERRED, scaled8} };
		p2.setLayout(new TableLayout(size2));
		p2.add(new JLabel("Parent Molecule"), "1,1");
		p2.add(new JLabel("Best Molecule"), "7,1");
		p2.add(new JLabel("Best molecules of current generation"), "1,6,7,6");
		mCompoundView = new JStructureView[6];
		mCompoundViewBorder = new ShadowBorder[6];
		mFitnessLabel = new JLabel[6];
		for (int i=0; i<6; i++) {
			mCompoundViewBorder[i] = new ShadowBorder();
			mCompoundView[i] = new JStructureView(DnDConstants.ACTION_COPY_OR_MOVE, DnDConstants.ACTION_NONE);
			mCompoundView[i].setBorder(mCompoundViewBorder[i]);
			mCompoundView[i].setOpaque(true);
			mCompoundView[i].setDisplayMode(AbstractDepictor.cDModeSuppressChiralText);
			mFitnessLabel[i] = new JLabel("Fitness:");
			if (i == 0) {
				p2.add(mCompoundView[i], "1,3");
				p2.add(mFitnessLabel[i], "1,4");
				}
			else if (i == 1) {
				p2.add(mCompoundView[i], "7,3");
				p2.add(mFitnessLabel[i], "7,4");
				}
			else {
				p2.add(mCompoundView[i], ""+(i*2-3)+",8");
				p2.add(mFitnessLabel[i], ""+(i*2-3)+",9");
				}
			}
		mLabelGeneration = new JLabel();
		p2.add(mLabelGeneration, "3,1");

		mLabelRun = new JLabel();
		p2.add(mLabelRun, "5,1");

		mFitnessEvolutionPanel = new FitnessEvolutionPanel();
		p2.add(mFitnessEvolutionPanel, "3,3,5,3");

		JPanel bp = new JPanel();
		bp.setBorder(BorderFactory.createEmptyBorder(scaled8+scaled4, scaled8, scaled8, scaled8));
		bp.setLayout(new BorderLayout());
		JPanel ibp = new JPanel();
		ibp.setLayout(new GridLayout(1, 2, scaled8, 0));
		if (isInteractive()) { // if in macro, we always take the data generated so far
			JButton buttonCancel = new JButton("Cancel");
			buttonCancel.addActionListener(this);
			ibp.add(buttonCancel);
			}
		JButton buttonStop = new JButton("Stop");
		buttonStop.addActionListener(this);
		ibp.add(buttonStop);
		bp.add(ibp, BorderLayout.EAST);

		mProgressPanel = new JProgressPanel(false);
		bp.add(mProgressPanel, BorderLayout.WEST);

		JDialog dialog = new JDialog(mParentFrame, TASK_NAME, true);
		dialog.getContentPane().add(p2, BorderLayout.CENTER);
		dialog.getContentPane().add(bp, BorderLayout.SOUTH);
		dialog.getRootPane().setDefaultButton(buttonStop);

		dialog.pack();
		dialog.setLocationRelativeTo(mParentFrame);
		return dialog;
		}

	public void actionPerformed(ActionEvent e) {
		if (e.getActionCommand().equals("Cancel") && !mStopProcessing) {
			mProgressPanel.startProgress("Cleaning Up...", 0, 0);
			mStopProcessing = true;
			mKeepData = false;
			}
		else if (e.getActionCommand().equals("Stop") && !mStopProcessing) {
			mProgressPanel.startProgress("Finishing...", 0, 0);
			mStopProcessing = true;
			mKeepData = true;
			}
		}

	@Override
	public boolean isConfigurable() {
		return true;
		}

	@Override
	public boolean isConfigurationValid(Properties configuration, boolean isLive) {
		String startSet = configuration.getProperty(PROPERTY_START_COMPOUNDS, "");
		if (startSet.length() != 0) {
			for (String idcode:startSet.split("\\t")) {
				try {
					new IDCodeParser(true).getCompactMolecule(idcode).validate();
					}
				catch (Exception e) {
					showErrorMessage("Some of your first generation compounds are invalid:\n"+e.toString());
					return false;
					}
				}
			}

		try {
			Integer.parseInt(configuration.getProperty(PROPERTY_RUN_COUNT, DEFAULT_RUNS));
			int survivalCount = Integer.parseInt(configuration.getProperty(PROPERTY_SURVIVAL_COUNT, DEFAULT_SURVIVALS));
			int generationSize = Integer.parseInt(configuration.getProperty(PROPERTY_GENERATION_SIZE, DEFAULT_COMPOUNDS));
			String generations = configuration.getProperty(PROPERTY_GENERATION_COUNT, DEFAULT_GENERATIONS);
			if (!generations.equals(GENERATIONS_AUTOMATIC) && !generations.equals(GENERATIONS_UNLIMITED))
				Integer.parseInt(configuration.getProperty(PROPERTY_GENERATION_COUNT, DEFAULT_GENERATIONS));

			if (survivalCount >= generationSize/2) {
				showErrorMessage("The number of compounds surviving a generation\nshould be less than half of the generation size.");
				return false;
				}
			if (survivalCount < 1) {
				showErrorMessage("The number of compounds surviving a generation is invalid.");
				return false;
				}
			if (generationSize < 1) {
				showErrorMessage("The number of compounds per generation is invalid.");
				return false;
				}
			}
		catch (NumberFormatException nfe) {
			showErrorMessage("Survival count, generation count, generation size, or run count are not numeric.");
			return false;
			}

		int fitnessOptionCount = Integer.parseInt(configuration.getProperty(PROPERTY_FITNESS_PARAM_COUNT, "0"));
		if (fitnessOptionCount == 0) {
			showErrorMessage("No fitness criteria defined.");
			return false;
			}
		for (int i=0; i<fitnessOptionCount; i++) {
			String errorMsg = FitnessOption.getParamError(configuration.getProperty(PROPERTY_FITNESS_PARAM_CONFIG+i, ""));
			if (errorMsg != null) {
				showErrorMessage(errorMsg);
				return false;
				}
			}

		return true;
		}

	@Override
	public void runTask(Properties configuration) {
		mControllingDialog = createControllingDialog();

		Thread t = new Thread(this, "DETaskEvolutionaryLibrary");
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();

		mControllingDialog.setVisible(true);
		}

	public void run() {
		try {
			runEvolution();
			}
		catch (Exception e) {
			e.printStackTrace();
			}
		catch (OutOfMemoryError e) {
			final String message = "Out of memory. Launch DataWarrior with Java option -Xms???m or -Xmx???m.";
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(mParentFrame, message) );
			}
		}

	private void runEvolution() {
		Properties configuration = getTaskConfiguration();

		final int survivalCount = Integer.parseInt(configuration.getProperty(PROPERTY_SURVIVAL_COUNT, DEFAULT_SURVIVALS));
		int generationSize = Integer.parseInt(configuration.getProperty(PROPERTY_GENERATION_SIZE, DEFAULT_COMPOUNDS));
		String generationsString = configuration.getProperty(PROPERTY_GENERATION_COUNT, DEFAULT_GENERATIONS);
		int generationCount = generationsString.equals(GENERATIONS_AUTOMATIC) ? Integer.MAX_VALUE-1
							: generationsString.equals(GENERATIONS_UNLIMITED) ? Integer.MAX_VALUE : Integer.parseInt(generationsString);
		if (!isInteractive()
		 && generationCount == Integer.MAX_VALUE)
			generationCount = Integer.MAX_VALUE - 1;	// don't allow unlimited processing, if is running a macro

		int fitnessOptionCount = Integer.parseInt(configuration.getProperty(PROPERTY_FITNESS_PARAM_COUNT));
		final FitnessOption[] fitnessOption = new FitnessOption[fitnessOptionCount];
		for (int i=0; i<fitnessOptionCount; i++)
			fitnessOption[i] = FitnessOption.createFitnessOption(configuration.getProperty(PROPERTY_FITNESS_PARAM_CONFIG+i), this);

		int kind = findListIndex(configuration.getProperty(PROPERTY_COMPOUND_KIND), COMPOUND_KIND_CODE, 0);
		int runCount = Integer.parseInt(configuration.getProperty(PROPERTY_RUN_COUNT, DEFAULT_RUNS));

		mCurrentResultID = new AtomicInteger(0);

		mProgressPanel.startProgress("1st generation...", 0, 0);

		// Create the result set for all results starting with the start generation.
		final TreeSet<EvolutionResult>[] completeResultSet = new TreeSet[runCount];

		Mutator mutator = new Mutator("/resources/"+COMPOUND_KIND_FILE[kind]);

		mKeepData = true;	// default if is not cancelled

		for (int run=0; run<runCount; run++) {
			if (runCount > 1)
				mLabelRun.setText("Run: "+(run+1));

			ConcurrentSkipListSet<String> moleculeHistory = new ConcurrentSkipListSet<>();

			completeResultSet[run] = new TreeSet<>();

			// Compile first parent generation including fitness calculation
			TreeSet<EvolutionResult> parentGeneration = new TreeSet<>();
			String startSet = configuration.getProperty(PROPERTY_START_COMPOUNDS, "");
			if (startSet.length() == 0) {
				ConcurrentLinkedQueue<StereoMolecule> compounds = new ConcurrentLinkedQueue<>();
				UIDelegateELib.createRandomStartSet(mProgressPanel, 4*survivalCount, kind, compounds);
				for (StereoMolecule compound:compounds) {
					if (!mStopProcessing)
						parentGeneration.add(new EvolutionResult(compound,
								new Canonizer(compound).getIDCode(), null, fitnessOption, mCurrentResultID.incrementAndGet(), run));
					}
				}
			else {
				for (String idcode : startSet.split("\\t"))
					if (!mStopProcessing)
						parentGeneration.add(new EvolutionResult(new IDCodeParser(true).getCompactMolecule(idcode),
								idcode, null, fitnessOption, mCurrentResultID.incrementAndGet(), run));
				}

			int childCompoundCount = generationSize / (2*survivalCount);
			mProgressPanel.startProgress("Evolving...", 0, (generationCount>=Integer.MAX_VALUE-1) ? 0 : generationCount*survivalCount*2);

			// Create the result set for all results starting with the start generation.
			completeResultSet[run].addAll(parentGeneration);

			mBestFitness = new AtomicFloat(0f);

			float bestFitness = 0;
			float bestFitnessGeneration = -1;

			for (int generation=0; (generation<generationCount) && !mStopProcessing; generation++) {
				mLabelGeneration.setText("Generation: "+(generation+1));

				// use all survived molecules from recent generation as parent structures
				ConcurrentSkipListSet<EvolutionResult> currentGeneration = new ConcurrentSkipListSet<>();

				// Create and start threads processing new entries from mMutationQueue and creating current generation results
				Thread[] mutationThread = launchMutationConsumers(currentGeneration, moleculeHistory, survivalCount, fitnessOption, run);

				int parentIndex = 0;
				for (EvolutionResult parentResult:parentGeneration) {
					if (mStopProcessing)
						break;

					parentResult.ensureCoordinates();
					mCompoundView[0].structureChanged(parentResult.getMolecule());
					mFitnessLabel[0].setText("Fitness: "+(float)((int)(100000*parentResult.getOverallFitness()))/100000);

					if (parentResult.getMutationList() == null)
						parentResult.setMutationList(mutator.generateMutationList(parentResult.getMolecule(), Mutator.MUTATION_ANY, false));

					// Create mutated compound and add them into mMutationQueue
					generateNextGenerationCompounds(childCompoundCount, parentResult, mutator);

					if ((generationCount<Integer.MAX_VALUE-1))
						mProgressPanel.updateProgress(survivalCount*generation + parentIndex);

					parentIndex++;
					}

				// Generate fitness limits for up to survivalCount structures from older generations.
				float[] fitnessLimit = new float[survivalCount];
				parentIndex = 0;
				for (EvolutionResult parentResult: parentGeneration) {
					if (parentIndex == survivalCount)
						break;
					fitnessLimit[parentIndex++] = parentResult.getOverallFitness();
					}

				// now also process previous best ranking molecules as parent structures
				if (generation != 0) {
					int resultIndex = 0;
					for (EvolutionResult parentResult:completeResultSet[run]) {
						if (mStopProcessing
						 || resultIndex == survivalCount
						 || parentResult.getOverallFitness() < fitnessLimit[resultIndex])
							break;

						if (parentResult.getMutationList().size() == 0)
							continue;

						parentResult.ensureCoordinates();
						mCompoundView[0].structureChanged(parentResult.getMolecule());
						mFitnessLabel[0].setText("Fitness: "+(float)((int)(100000*parentResult.getOverallFitness()))/100000);

						// Create mutated compound and add them into mMutationQueue
						generateNextGenerationCompounds(childCompoundCount, parentResult, mutator);

						if ((generationCount<Integer.MAX_VALUE-1))
							mProgressPanel.updateProgress(survivalCount*generation*2+survivalCount+resultIndex);

						resultIndex++;
						}
					}

				for (int i=0; i<mutationThread.length; i++)
					try { mMutationQueue.put(MutationQueueEntry.END_ENTRY); } catch (InterruptedException e) {}
				for (Thread thread : mutationThread)
					try { thread.join(); } catch (InterruptedException e) {}

				if (currentGeneration.size() == 0) {
					if (isInteractive()) {
						try {
							SwingUtilities.invokeAndWait(() ->
								JOptionPane.showMessageDialog(mParentFrame, "No valid molecules could be made in most recent generation")
								);
							} catch (Exception e) {}
						}
					break;
					}

				int resultNo = 0;
				parentGeneration.clear();
				for (EvolutionResult r:currentGeneration) {
					r.setChildIndex(resultNo++);
					completeResultSet[run].add(r);
					parentGeneration.add(r);
					}

				mFitnessEvolutionPanel.updateEvolution(run, generation, currentGeneration);

				// In automatic mode stop if no improvement over AUTOMATIC_HISTORIC_GENERATIONS generations
				if (generationCount == Integer.MAX_VALUE-1) {
					if (bestFitness < mBestFitness.floatValue()) {
						bestFitness = mBestFitness.floatValue();
						bestFitnessGeneration = generation;
						}
					else if (generation >= bestFitnessGeneration + AUTOMATIC_HISTORIC_GENERATIONS) {
						break;
						}
					}
				}
			}

		if (!mKeepData) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					mControllingDialog.setVisible(false);
					mControllingDialog.dispose();
				} );
			} catch (Exception e) {}
		}
		else {
			for (int run=1; run<runCount; run++)
				completeResultSet[0].addAll(completeResultSet[run]);

			EvolutionResult[] result = completeResultSet[0].toArray(new EvolutionResult[0]);

			for (int foi=0; foi<fitnessOption.length; foi++) {
				FitnessOption fo = fitnessOption[foi];
				if (fo.hasDeferredColumnValues()) {
					AtomicInteger resultIndex = new AtomicInteger(0);
					mProgressPanel.startProgress("Calculating "+fo.getName()+" columns values...", 0, result.length);
					int threadCount = Runtime.getRuntime().availableProcessors();
					Thread[] thread = new Thread[threadCount];
					final int _foi = foi;
					for (int i=0; i<threadCount; i++) {
						thread[i] = new Thread(() -> {
							int ri;
							while ((ri = resultIndex.getAndIncrement()) < result.length) {
								result[ri].calculateDeferredColumnValue(_foi);
								mProgressPanel.updateProgress(ri);
								}
							} );
						thread[i].setPriority(Thread.MIN_PRIORITY);
						thread[i].setName("Custom Value Calculator "+i);
						thread[i].start();
						}
					for (int i = 0; i<threadCount; i++)
						try { thread[i].join(); } catch (InterruptedException e) {}
					}
				}

			try {
				SwingUtilities.invokeAndWait(() -> {
					mControllingDialog.setVisible(false);
					mControllingDialog.dispose();

					if (mKeepData) {
						mTargetFrame = mApplication.getEmptyFrame("Evolutionary Library");
						createDocument(result, fitnessOption, runCount != 1, runCount);
						}
					} );
				} catch (Exception e) {}
			}
		}

	private Thread[] launchMutationConsumers(ConcurrentSkipListSet<EvolutionResult> currentGeneration,
										 ConcurrentSkipListSet<String> moleculeHistory,
										 int survivalCount,
										 FitnessOption[] fitnessOption,
										 int run) {
		Thread[] mutationThread;
		int threadCount = Runtime.getRuntime().availableProcessors();
		mMutationQueue = new ArrayBlockingQueue<>(10 * threadCount);
		mutationThread = new Thread[threadCount];
		for (int i = 0; i<threadCount; i++) {
			mutationThread[i] = new Thread(() -> {
				while (true) {
					try {
						MutationQueueEntry entry = mMutationQueue.take();
						if (entry.isEnd())
							break;
						if (!mStopProcessing)
							processCandidate(entry, currentGeneration, moleculeHistory, survivalCount, fitnessOption, run);
					}
					catch (InterruptedException ie) {
						break;
					}
				}
			});
			mutationThread[i].setPriority(Thread.MIN_PRIORITY);
			mutationThread[i].setName(THREAD_NAME_PREFIX + i);
			mutationThread[i].start();
			}
		return mutationThread;
		}

	private void generateNextGenerationCompounds(int offspringCompounds, final EvolutionResult parentResult, final Mutator mutator) {
		int maxCount = Math.min(offspringCompounds, parentResult.getMutationList().size());
		for (int i=0; i<maxCount && !parentResult.getMutationList().isEmpty() && !mStopProcessing; i++) {
			StereoMolecule mol = new StereoMolecule(parentResult.getMolecule());
			mutator.mutate(mol, parentResult.getMutationList());
			try {
				mMutationQueue.put(new MutationQueueEntry(mol, parentResult));
				}
			catch (InterruptedException ie) {}
			}
		}

	private void processCandidate(MutationQueueEntry candidate,
	                              ConcurrentSkipListSet<EvolutionResult> currentGeneration,
	                              ConcurrentSkipListSet<String> moleculeHistory,
	                              int survivalCount,
	                              FitnessOption[] fitnessOption,
	                              int run) {

		String idcode = new Canonizer(candidate.mol).getIDCode();

		if (!moleculeHistory.add(idcode))
			return;

		EvolutionResult result = new EvolutionResult(candidate.mol, idcode, candidate.parentResult, fitnessOption, mCurrentResultID.incrementAndGet(), run);
		currentGeneration.add(result);
		if (currentGeneration.size() > survivalCount)
			currentGeneration.remove(currentGeneration.last());

		Iterator<EvolutionResult> iterator = currentGeneration.iterator();
		boolean structureInserted = false;
		for (int index=2; index<6; index++) {
			if (!iterator.hasNext()) {
				final int _index = index;
				SwingUtilities.invokeLater(() -> {
					mCompoundView[_index].structureChanged(null);
					mCompoundViewBorder[_index].setColor(null);
					mFitnessLabel[_index].setText("Fitness:");
					} );
				}
			else {
				EvolutionResult r = iterator.next();
				if (r.getIDCode().equals(idcode)) {
					structureInserted = true;
					r.ensureCoordinates();
					}
				if (structureInserted) {
					final int _index = index;
					SwingUtilities.invokeLater(() -> {
						mCompoundView[_index].structureChanged(r.getMolecule());
						mCompoundViewBorder[_index].setColor(createColorFromFitness(r.getOverallFitness()));
						mFitnessLabel[_index].setText("Fitness: "+(float)((int)(100000*r.getOverallFitness()))/100000);
						} );
					}
				}
			}

		while (mBestFitness.floatValue() < result.getOverallFitness()) {
			final float oldBestFitness = mBestFitness.floatValue();
			final float newBestFitness = result.getOverallFitness();
			if (oldBestFitness < result.getOverallFitness() && mBestFitness.compareAndSet(oldBestFitness, newBestFitness)) {
				SwingUtilities.invokeLater(() -> {
					mCompoundView[1].structureChanged(result.getMolecule());
					mCompoundViewBorder[1].setColor(createColorFromFitness(newBestFitness));
					mFitnessLabel[1].setText("Fitness: " + (float) ((int) (100000 * newBestFitness)) / 100000);
					} );
				}
			}
		}

	private Color createColorFromFitness(float fitness) {
		fitness = Math.max(0f, 2f*fitness-1f);  // we assign red->green to 0.5->1.0; in addition we distort to enlarge the upper part of the range
		return new Color(Color.HSBtoRGB((fitness*fitness/3f), 0.8f, 0.6f));
		}

	private void createDocument(EvolutionResult[] result, FitnessOption[] fitnessOption, boolean hasMultipleRuns, int runCount) {
		Arrays.sort(result, (r1, r2) -> (r1.getID() == r2.getID()) ? 0 : (r1.getID() < r2.getID()) ? -1 : 1);

		ArrayList<String> columnNameList = new ArrayList<>();
		columnNameList.add("ID");
		columnNameList.add("Parent ID");
		if (hasMultipleRuns)
			columnNameList.add("Run");
		columnNameList.add("Generation");
		columnNameList.add("Parent Generation");
		columnNameList.add("Child No");
		columnNameList.add("Fitness");

		int firstFitnessOptionColumn = 2+columnNameList.size();
		for (FitnessOption fo:fitnessOption)
			for (int i=0; i<fo.getResultColumnCount(); i++)
				columnNameList.add(fo.getResultColumnName(i));

		CompoundTableModel tableModel = mTargetFrame.getTableModel();
		tableModel.initializeTable(result.length, 2+columnNameList.size());

		tableModel.prepareStructureColumns(0, "Structure", false, true);
		int column = 2;
		for (String columnName:columnNameList)
			tableModel.setColumnName(columnName, column++);

		tableModel.setColumnProperty(3, CompoundTableModel.cColumnPropertyReferencedColumn, "ID");
		tableModel.setColumnProperty(3, CompoundTableModel.cColumnPropertyReferenceType,
										CompoundTableModel.cColumnPropertyReferenceTypeTopDown);
		tableModel.setColumnProperty(5, CompoundTableModel.cColumnPropertyReferencedColumn, "Generation");
		tableModel.setColumnProperty(5, CompoundTableModel.cColumnPropertyReferenceType,
										CompoundTableModel.cColumnPropertyReferenceTypeTopDown);

		column = firstFitnessOptionColumn;
		for (FitnessOption fo:fitnessOption)
			for (int i=0; i<fo.getResultColumnCount(); i++)
				fo.setResultColumnProperties(i, tableModel, column++);

		int row = 0;
		float[] maxFitness = new float[runCount];
		EvolutionResult[] bestResult = new EvolutionResult[runCount];
		for (EvolutionResult r:result) {
			if (maxFitness[r.getRun()] < r.getOverallFitness()) {
				maxFitness[r.getRun()] = r.getOverallFitness();
				bestResult[r.getRun()] = r;
				}

			tableModel.setTotalValueAt(r.getIDCode(), row, 0);
			column = 2;
			tableModel.setTotalValueAt(""+r.getID(), row, column++);
			tableModel.setTotalValueAt(""+r.getParentID(), row, column++);
			if (hasMultipleRuns)
				tableModel.setTotalValueAt(""+(1+r.getRun()), row, column++);
			tableModel.setTotalValueAt(""+(1+r.getGeneration()), row, column++);
			tableModel.setTotalValueAt(""+(1+r.getParentGeneration()), row, column++);
			tableModel.setTotalValueAt(""+(1+r.getChildIndex()), row, column++);
			tableModel.setTotalValueAt(DoubleFormat.toString(r.getOverallFitness(), 5), row, column++);
			for (int foi=0; foi<fitnessOption.length; foi++)
				for (int i=0; i<fitnessOption[foi].getResultColumnCount(); i++)
					tableModel.setTotalValueAt(r.getResultValue(foi, i), row, column++);
			row++;
			}

		tableModel.finalizeTable(CompoundTableEvent.cSpecifierNoRuntimeProperties, mProgressPanel);

		setRuntimeSettings(createHitlist(result, bestResult), hasMultipleRuns);
		}

	private int createHitlist(EvolutionResult[] result, EvolutionResult[] bestResult) {
		CompoundTableModel tableModel = mTargetFrame.getTableModel();
		CompoundTableListHandler hitlistHandler = tableModel.getListHandler();
		String name = hitlistHandler.createList("Direct Route", -1, CompoundTableListHandler.EMPTY_LIST, -1, null, false);
		int hitlistFlagNo = hitlistHandler.getListFlagNo(name);
		for (EvolutionResult bestResultOfRun:bestResult) {
			if (bestResultOfRun != null) {
				int wantedID = bestResultOfRun.getID();
				for (int row=result.length-1; row>=0; row--) {
					if (wantedID == result[row].getID()) {
						wantedID = result[row].getParentID();
						hitlistHandler.addRecordSilent(tableModel.getTotalRecord(row), hitlistFlagNo);
						}
					}
				}
			}
		return hitlistHandler.getListIndex(name);
		}

	private void setRuntimeSettings(final int hitlist, final boolean splitByRun) {
		SwingUtilities.invokeLater(() -> {
			mTargetFrame.getMainFrame().getMainPane().addStructureView("Structure", null, 0);

			mTargetFrame.getMainFrame().getPruningPanel().removeAllFilters();	// this is the automatically added list filter
			mTargetFrame.getMainFrame().getPruningPanel().addDefaultFilters();

			VisualizationPanel2D vpanel1 = mTargetFrame.getMainFrame().getMainPane().add2DView("Fitness Evolution", null);
			vpanel1.setAxisColumnName(0, "Generation");
			vpanel1.setAxisColumnName(1, "Fitness");

			JVisualization2D visualization = (JVisualization2D)vpanel1.getVisualization();
			visualization.setMarkerSize(0.6f, false);
			visualization.setPreferredChartType(JVisualization.cChartTypeScatterPlot, -1, -1);
			visualization.setConnectionColumns(mTargetFrame.getTableModel().findColumn("Parent ID"), -1);
			visualization.getMarkerColor().setColor(CompoundTableListHandler.getColumnFromList(hitlist));
			visualization.setFocusList(hitlist);
			visualization.setViewBackground(new Color(VIEW_BACKGROUND));
			if (splitByRun)
				visualization.setSplittingColumns(mTargetFrame.getTableModel().findColumn("Run"), -1, 1f, false);
			} );
		}
	}

class MutationQueueEntry {
	public static MutationQueueEntry END_ENTRY = new MutationQueueEntry(null, null);
	StereoMolecule mol;
	EvolutionResult parentResult;
	public MutationQueueEntry(StereoMolecule mol, EvolutionResult parentResult) {
		this.mol = mol;
		this.parentResult = parentResult;
		}

	public boolean isEnd() {
		return mol == null && parentResult == null;
		}
	}