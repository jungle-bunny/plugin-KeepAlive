/*
 * Keep Alive Plugin
 * Copyright (C) 2012 Jeriadoc
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package keepalive;

import freenet.client.HighLevelSimpleClientImpl;
import freenet.pluginmanager.PluginRespirator;
import keepalive.service.reinserter.Reinserter;
import keepalive.web.AdminPage;
import pluginbase.PluginBase;

import java.io.File;

public class Plugin extends PluginBase {

	private static final String version = "0.3.3.8-pre2-RW";

	private Reinserter reinserter;
	private long propSavingTimestamp;
	private HighLevelSimpleClientImpl hlsc;

	public Plugin() {
		super("KeepAlive", "KeepAlive", "prop.txt");
		setVersion(version);
		addPluginToMenu("KeepAlive", "Reinsert sites and files in the background");
		clearLog();
	}

	@Override
	public void runPlugin(PluginRespirator pr) {
		super.runPlugin(pr);
		try {
			hlsc = (HighLevelSimpleClientImpl) pluginContext.node.clientCore.makeClient((short) 5, false, true);

			// migrate from 0.2 to 0.3
			if (getProp("version") == null || !getProp("version").substring(0, 3).equals("0.3")) {
				int[] ids = getIds();

				// remove boost params
				for (int aId : ids) {
					removeProp("boost_" + aId);
				}

				// empty all block list
				for (int aId : ids) {
					setProp("blocks_" + aId, "?");
				}

				setProp("version", version);
			}

			// initial values
			if (getProp("loglevel") == null) setIntProp("loglevel", 1);
			if (getProp("ids") == null) setProp("ids", "");
			if (getProp("power") == null) setIntProp("power", 6);
			if (getProp("active") == null) setIntProp("active", -1);
			if (getProp("splitfile_tolerance") == null) setIntProp("splitfile_tolerance", 66);
			if (getProp("splitfile_test_size") == null) setIntProp("splitfile_test_size", 18);
			if (getProp("log_links") == null) setIntProp("log_links", 1);
			if (getProp("log_utc") == null) setIntProp("log_utc", 1);
			if (getIntProp("log_utc") == 1) setTimezoneUTC();
			saveProp();

			// build page and menu
			addPage(new AdminPage(this, pluginContext.node.clientCore.formPassword));
			addMenuItem("Documentation", "Go to the documentation site",
				 "/USK@l9wlbjlCA7kfcqzpBsrGtLoAB4-Ro3vZ6q2p9bQ~5es,bGAKUAFF8UryI04sxBKnIQSJWTSa08BDS-8jmVQdE4o,AQACAAE/keepalive/15", true);

			// start reinserter
			int activeProp = getIntProp("active");
			if (activeProp != -1) {
				startReinserter(activeProp);
			}

		} catch (Exception e) {
			log("Plugin.runPlugin(): " + e.getMessage(), 0);
		}
	}

	public void startReinserter(int nSiteId) {
		try {

			(new Reinserter(this, nSiteId)).start();

		} catch (Exception e) {
			log("Plugin.startReinserter(): " + e.getMessage(), 0);
		}
	}

	public synchronized void stopReinserter() {
		try {

			if (reinserter != null) {
				reinserter.terminate();
			}

		} catch (Exception e) {
			log("Plugin.stopReinserter(): " + e.getMessage(), 0);
		}
	}

	public int[] getIds() {
		try {

			if (getProp("ids") == null || getProp("ids").equals("")) {
				return new int[] {};
			} else {
				String[] ids = getProp("ids").split(",");
				int[] intIds = new int[ids.length];
				for (int i = 0; i < intIds.length; i++) {
					intIds[i] = Integer.parseInt(ids[i]);
				}

				return intIds;
			}

		} catch (Exception e) {
			log("Plugin.getIds(): " + e.getMessage(), 0);
			return null;
		}
	}

	public int[] getSuccessValues(int siteId) {
		try {

			// available blocks
			int success = 0;
			int failed = 0;
			String[] successMap = getProp("success_" + siteId).split(",");
			if (successMap.length >= 2) {
				for (int i = 0; i < successMap.length; i += 2) {
					success += Integer.parseInt(successMap[i]);
					failed += Integer.parseInt(successMap[i + 1]);
				}
			}

			// available segments
			int availableSegments = 0;
			String successSegments = getProp("success_segments_" + siteId);
			int lastTriedSegment = getIntProp("segment_" + siteId);
			if (successSegments != null) {
				if (lastTriedSegment >= successSegments.length()) {
					log("Plugin.getSuccessValues(): List of success_segments too short for siteId " +
							siteId + "! " + successSegments.length() + " vs " + lastTriedSegment + 1, 0);
				}

				for (int i = 0; i <= lastTriedSegment && i < successSegments.length(); i++) {
					if (successSegments.charAt(i) == '1') {
						availableSegments++;
					}
				}
			}

			return new int[] {success, failed, availableSegments};

		} catch (Exception e) {
			log("Plugin.getSuccessValues(): " + e.getMessage(), 0);
			return null;
		}
	}

	public String getLogFilename(int siteId) {
		return "log" + siteId + ".txt";
	}

	public String getBlockListFilename(int siteId) {
		return "keys" + siteId + ".txt";
	}

	@Override
	public void saveProp() {
		if (propSavingTimestamp < System.currentTimeMillis() - 10 * 1000) {
			super.saveProp();
			propSavingTimestamp = System.currentTimeMillis();
		}
	}

	@Override
	public void terminate() {
		super.terminate();
		if (reinserter != null) {
			reinserter.terminate();
		}
		log("plugin terminated", 0);
	}

	public HighLevelSimpleClientImpl getFreenetClient() {
		return hlsc;
	}

	public void setReinserter(Reinserter reinserter) {
		this.reinserter = reinserter;
	}

	public synchronized boolean isDuplicate(String uri) {
		try {
			for (int i : getIds()) {
				if (getProp("uri_" + i).equals(uri)) {
					return true;
				}
			}
		} catch (Exception e) {
			log("Plugin.isDuplicate(): " + e.getMessage(), 2);
		}
		return false;
	}

	public void removeUri(int id) throws Exception {
		// stop reinserter
		if (id == getIntProp("active")) {
			stopReinserter();
		}

		// remove log and key files
		File file = new File(getPluginDirectory() + getLogFilename(id));
		if (file.exists()) {
			if (!file.delete()) {
				log("Plugin.removeUri(): remove log files was not successful.", 1);
			}
		}
		file = new File(getPluginDirectory() + getBlockListFilename(id));
		if (file.exists()) {
			if (!file.delete()) {
				log("Plugin.removeUri(): remove key files was not successful.", 1);
			}
		}

		// remove items
		removeProp("uri_" + id);
		removeProp("blocks_" + id);
		removeProp("success_" + id);
		removeProp("success_segments_" + id);
		removeProp("segment_" + id);
		removeProp("history_" + id);
		String ids = ("," + getProp("ids")).replaceAll("," + id + ",", ",");
		setProp("ids", ids.substring(1));
		saveProp();
	}
}
