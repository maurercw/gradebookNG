package org.sakaiproject.gradebookng.tool.panels;

import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.panel.Panel;
import org.sakaiproject.gradebookng.business.model.GbAssignment;
import org.sakaiproject.gradebookng.tool.model.GbAssignmentModel;

/**
 * The panel for the add grade item window
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
public class AddGradeItemPanel extends Panel {

	private static final long serialVersionUID = 1L;

	public AddGradeItemPanel(String id) {
		super(id);

        Form<?> form = new Form("form");

		add(new AddGradeItemPanelContent("subComponents", new GbAssignmentModel(new GbAssignment())));


	}


}
