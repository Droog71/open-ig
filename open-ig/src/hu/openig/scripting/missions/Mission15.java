/*
 * Copyright 2008-2012, David Karnok 
 * The file is part of the Open Imperium Galactica project.
 * 
 * The code should be distributed under the LGPL license.
 * See http://www.gnu.org/licenses/lgpl.html for details.
 */

package hu.openig.scripting.missions;

import hu.openig.core.Action0;
import hu.openig.model.ObjectiveState;

/**
 * Mission 15: see spy, talk to Colonel, talk to kelly.
 * @author akarnokd, 2012.01.18.
 */
public class Mission15 extends Mission {
	@Override
	public void onTime() {
		if (world.level != 2) {
			return;
		}
		if (helper.canStart("Mission-15")) {
			helper.clearMissionTime("Mission-15");
			world.env.stopMusic();
			world.env.playVideo("interlude/spy_on_johnson", new Action0() {
				@Override
				public void invoke() {
					world.currentTalk = "kelly";
					helper.showObjective("Mission-15");
					helper.showObjective("Mission-15-Task-1");
					helper.setMissionTime("Mission-15-Task-1-Timeout", helper.now() + 7 * 24);
					world.env.playMusic();
				}
			});
		}
		if (world.recordWatched && helper.objective("Mission-15-Task-2").state == ObjectiveState.ACTIVE) {
			// record watched in time
			helper.setObjectiveState("Mission-15-Task-2", ObjectiveState.SUCCESS);
			helper.clearMissionTime("Mission-15-Task-2-Timeout");
			
			helper.send("Douglas-Report-Spy").visible = true;

			helper.showObjective("Mission-15-Task-3");
			helper.setMissionTime("Mission-15-Task-3-Timeout", helper.now() + 7 * 24);
			
		}
		// TIMEOUTS ---------------------------------------------------------------
		if (helper.isMissionTime("Mission-15-Task-1-Timeout")) {
			// record not taken in time
			helper.clearMissionTime("Mission-15-Task-1-Timeout");
			helper.setObjectiveState("Mission-15-Task-1", ObjectiveState.FAILURE);
			helper.setObjectiveState("Mission-15", ObjectiveState.FAILURE);
			world.currentTalk = null;
		}
		if (helper.isMissionTime("Mission-15-Task-2-Timeout")) {
			// record not taken in time
			helper.clearMissionTime("Mission-15-Task-2-Timeout");
			helper.setObjectiveState("Mission-15-Task-2", ObjectiveState.FAILURE);
			helper.setObjectiveState("Mission-15", ObjectiveState.FAILURE);
		}
		if (helper.isMissionTime("Mission-15-Task-3-Timeout")) {
			// not reported in time
			helper.clearMissionTime("Mission-15-Task-3-Timeout");
			helper.setObjectiveState("Mission-15-Task-3", ObjectiveState.FAILURE);
			helper.setObjectiveState("Mission-15", ObjectiveState.FAILURE);
			helper.setTimeout("Mission-15-Hide", 13000);

		}
		// hide tasks ---------------------------------------------------------------
		if (helper.isTimeout("Mission-15-Hide")) {
			helper.clearTimeout("Mission-15-Hide");
			helper.objective("Mission-15").visible = false;
			helper.send("Douglas-Report-Spy").visible = false;
			world.currentTalk = null;
		}
	}
	
	@Override
	public void onMessageSeen(String id) {
		if (world.level == 2) {
			if ("Douglas-Report-Spy".equals(id)) {
				if (helper.setObjectiveState("Mission-15-Task-3", ObjectiveState.SUCCESS)) {
					helper.setObjectiveState("Mission-15", ObjectiveState.SUCCESS);
					helper.setTimeout("Mission-15-Task-3-Hide", 13000);
					helper.clearMissionTime("Mission-15-Task-3-Timeout");
					helper.setTimeout("Mission-15-Hide", 13000);
				}
			}
		}
	}
	@Override
	public void onTalkCompleted() {
		if (world.level == 2 && "kelly".equals(world.currentTalk)) {
			if (helper.setObjectiveState("Mission-15-Task-1", ObjectiveState.SUCCESS)) {
				world.allowRecordMessage = true;
				helper.showObjective("Mission-15-Task-2");
				helper.clearMissionTime("Mission-15-Task-1-Timeout");
				// record message timeout
				helper.setMissionTime("Mission-15-Task-2-Timeout", helper.now() + 7 * 24);
			}
		}
	}
}