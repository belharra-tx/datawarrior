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

package com.actelion.research.datawarrior.task.chem.rxn;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.StereoMolecule;
import com.actelion.research.chem.coords.CoordinateInventor;
import com.actelion.research.chem.descriptor.DescriptorConstants;
import com.actelion.research.chem.reaction.Reaction;
import com.actelion.research.datawarrior.DEFrame;
import com.actelion.research.datawarrior.task.chem.DETaskAbstractFromReaction;
import info.clearthought.layout.TableLayout;

import javax.swing.*;
import java.util.Properties;

public class DETaskExtractReactants extends DETaskAbstractFromReaction {
	public static final String TASK_NAME = "Extract Reactants";

	private static final String PROPERTY_CREATE_FFP = "createFFP";

	private JCheckBox mCheckBoxCreateFFP;
	private boolean mCreateFFP;

	public DETaskExtractReactants(DEFrame parent) {
		super(parent, DESCRIPTOR_NONE, false, true);
		}

	@Override
	protected int getNewColumnCount() {
		return 2 + (mCreateFFP ? 1 : 0);
		}

	@Override
	protected String getNewColumnName(int column) {
		// is done by setNewColumnProperties()
		return "";
		}

	@Override
	public boolean hasExtendedDialogContent() {
		return true;
		}

	@Override
	public JPanel getExtendedDialogContent() {
		double[][] size = { {TableLayout.PREFERRED}, {TableLayout.PREFERRED} };

		mCheckBoxCreateFFP = new JCheckBox("Create FFP512 descriptor");

		JPanel ep = new JPanel();
		ep.setLayout(new TableLayout(size));
		ep.add(mCheckBoxCreateFFP, "0,0");
		return ep;
	}

	@Override
	public Properties getDialogConfiguration() {
		Properties configuration = super.getDialogConfiguration();
		configuration.setProperty(PROPERTY_CREATE_FFP, mCheckBoxCreateFFP.isSelected() ? "true" : "false");
		return configuration;
		}

	@Override
	public void setDialogConfiguration(Properties configuration) {
		super.setDialogConfiguration(configuration);
		mCheckBoxCreateFFP.setSelected("true".equals(configuration.getProperty(PROPERTY_CREATE_FFP)));
		}

	@Override
	public void setDialogConfigurationToDefault() {
		super.setDialogConfigurationToDefault();
		mCheckBoxCreateFFP.setSelected(true);
		}


	@Override
	public String getTaskName() {
		return TASK_NAME;
		}

	@Override
	protected void setNewColumnProperties(int firstNewColumn) {
		String sourceColumnName = getTableModel().getColumnTitle(getChemistryColumn());
		String descriptorName = mCreateFFP ? DescriptorConstants.DESCRIPTOR_FFP512.shortName : null;
		getTableModel().prepareStructureColumns(firstNewColumn, "Reactants of " + sourceColumnName, true, descriptorName);
		}

	@Override
	protected boolean preprocessRows(Properties configuration) {
		mCreateFFP = "true".equals(configuration.getProperty(PROPERTY_CREATE_FFP));
		return super.preprocessRows(configuration);
		}

	@Override
	public void processRow(int row, int firstNewColumn, StereoMolecule containerMol, int threadIndex) {
		Reaction rxn = getChemicalReaction(row);
		if (rxn != null & rxn.getReactants() != 0) {
			StereoMolecule reactants = rxn.getReactant(0);
			for (int i=1; i<rxn.getReactants(); i++)
				reactants.addMolecule(rxn.getReactant(i));
			new CoordinateInventor().invent(reactants);
			Canonizer canonizer = new Canonizer(reactants);
			getTableModel().setTotalValueAt(canonizer.getIDCode(), row, firstNewColumn);
			getTableModel().setTotalValueAt(canonizer.getEncodedCoordinates(), row, firstNewColumn+1);
			}
		}
	}
