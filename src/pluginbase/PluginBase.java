/*
 * Plugin Base Package
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
package pluginbase;

import pluginbase.de.todesbaum.util.freenet.fcp2.Connection;
import pluginbase.de.todesbaum.util.freenet.fcp2.ConnectionListener;
import pluginbase.de.todesbaum.util.freenet.fcp2.Message;
import pluginbase.de.todesbaum.util.freenet.fcp2.Node;
import freenet.clients.http.PageMaker;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.plugins.helpers1.PluginContext;
import freenet.support.plugins.helpers1.WebInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.TimeZone;
import java.util.TreeMap;

abstract public class PluginBase implements FredPlugin, FredPluginThreadless,
		FredPluginVersioned, FredPluginL10n, ConnectionListener {

	public PluginContext pluginContext;

	PageMaker pagemaker;
	WebInterface webInterface;
	Connection fcpConnection;

	private Properties prop;
	private String strTitle;
	private String strPath;
	private String strPropFilename;
	private String strMenuTitle = null;
	private String strMenuTooltip = null;
	private String strVersion = "0.0";
	private SimpleDateFormat dateFormat;
	private TreeMap mPages = new TreeMap();
	private TreeMap<String, RandomAccessFile> mLogFiles = new TreeMap<>();

	public PluginBase(String strPath, String strTitle, String strPropFilename) {
		try {

			this.strPath = strPath;
			this.strTitle = strTitle;
			this.strPropFilename = strPropFilename;

			// prepare and clear log file
			(new File(strPath)).mkdir();
			initLog("log.txt");
			dateFormat = new SimpleDateFormat("yyyy.MM.dd_HH.mm_ss");
			dateFormat.setTimeZone(TimeZone.getDefault());

			// load properties
			loadProp();
			if (getProp("loglevel") == null) {
				setIntProp("loglevel", 0);
			}

		} catch (Exception e) {
			log("PluginBase(): " + e.getMessage(), 1);
		}
	}

	String getCategory() {
		return strTitle.replaceAll(" ", "_");
	}

	String getPath() {
		return "/" + strPath;
	}

	public String getPluginDirectory() {
		return strPath + "/";
	}

	@Override
	public void runPlugin(PluginRespirator pr) {          // FredPlugin
		try {

			log(getVersion() + " started");

			// init plugin context
			pagemaker = pr.getPageMaker();
			pluginContext = new PluginContext(pr);
			webInterface = new WebInterface(pluginContext);

			// fcp connection
			connectFcp();

			// add menu
			pagemaker.removeNavigationCategory(getCategory());
			if (strMenuTitle != null) {
				webInterface.addNavigationCategory(getPath() + "/", getCategory(), strMenuTooltip, this);
			}

		} catch (Exception e) {
			log("PluginBase.runPlugin(): " + e.getMessage(), 1);
		}
	}

	@Override
	public String getVersion() {                         // FredPluginVersioned
		return strTitle + " " + strVersion;
	}

	/**
	 *
	 * @param strKey
	 * @return This method return phrase in right local
	 */
	@Override
	public String getString(String strKey) {               // FredPluginL10n
		return strKey;
	}

	/**
	 *
	 * @param language
	 */
	@Override
	public void setLanguage(LANGUAGE language) {         // FredPluginL10n
		LANGUAGE nodeLanguage = language;
	}

	@Override
	public void terminate() {                            // FredPlugin
		try {

			log("plugin base terminates");
			saveProp();
			fcpConnection.disconnect();
			fcpConnection = null;
			webInterface.kill();
			webInterface = null;
			pagemaker.removeNavigationCategory(getCategory());
			log("plugin base terminated");
			for (RandomAccessFile file : mLogFiles.values()) {
				file.close();
			}

		} catch (IOException e) {
			log("PluginBase.terminate(): " + e.getMessage(), 1);
		}
	}

	@Override
	public void messageReceived(Connection connection, Message message) {            // ConnectionListener
		try {

			// errors
			if (message.getName().equals("ProtocolError")) {
				log("ProtocolError: " + message.get("CodeDescription"));

			} else if (message.getName().equals("IdentifierCollision")) {
				log("IdentifierCollision");

				// redirect deprecated usk edition
			} else if (message.getName().equals("GetFailed") && (message.get("RedirectUri") != null) &&
					!message.get("RedirectUri").equals("")) {
				log("USK redirected (" + message.getIdentifier() + ")");
				// reg new edition
				String cPagename = message.getIdentifier().split("_")[0];
				PageBase page = (PageBase) mPages.get(cPagename);
				if (page != null) {
					page.updateRedirectUri(message);
				}
				// redirect
				FcpCommands fcpCommand = new FcpCommands(fcpConnection, null);
				fcpCommand.setCommandName("ClientGet");
				fcpCommand.field("Identifier", message.getIdentifier());
				fcpCommand.field("URI", message.get("RedirectUri"));
				fcpCommand.send();

				// register message
			} else {
				log("fcp: " + message.getName() + " (" + message.getIdentifier() + ")");
				String cPagename = message.getIdentifier().split("_")[0];
				PageBase page = (PageBase) mPages.get(cPagename);
				if (page != null) {
					page.addMessage(message);
				}
			}

		} catch (Exception e) {
			log("PluginBase.messageReceived(): " + e.getMessage(), 1);
		}
	}

	@Override
	public void connectionTerminated(Connection connection) {                        // ConnectionListener
		log("fcp connection terminated");
	}

	private synchronized void connectFcp() {
		try {

			if (fcpConnection == null || !fcpConnection.isConnected()) {
				fcpConnection = new Connection(new Node("localhost"), "connection_" + System.currentTimeMillis());
				fcpConnection.addConnectionListener(this);
				fcpConnection.connect();
			}

		} catch (IOException e) {
			log("PluginBase.connectFcp(): " + e.getMessage(), 1);
		}
	}

	private void loadProp() {
		try {

			if (strPropFilename != null) {
				prop = new Properties();
				File file = new File(strPath + "/" + strPropFilename);
				File oldFile = new File(strPath + "/" + strPropFilename + ".old");

				FileInputStream is;
				//always load from the backup if it exists, it is (almost?)
				//guaranteed to be good.
				if (oldFile.exists()) {
					is = new FileInputStream(oldFile);
				} else if (file.exists()) {
					is = new FileInputStream(file);
				} else {
					throw new Exception("No Prop file found.");
				}
				prop.load(is);
				is.close();
			}

		} catch (Exception e) {
			log("PluginBase.loadProp(): " + e.getMessage(), 1);
		}
	}

	// ******************************************
	// methods to use in the derived page class:
	// ******************************************
	// log files
	private synchronized void initLog(String strFilename) {
		try {

			if (!mLogFiles.containsKey(strFilename)) {
				RandomAccessFile file = new RandomAccessFile(strPath + "/" + strFilename, "rw");
				file.seek(file.length());
				mLogFiles.put(strFilename, file);
			}

		} catch (IOException e) {
			log("PluginBase.initLog(): " + e.getMessage());
		}
	}

	public synchronized void log(String strFilename, String cText, int nLogLevel) {
		try {

			if (nLogLevel <= getIntProp("loglevel")) {
				initLog(strFilename);
				mLogFiles.get(strFilename).writeBytes(dateFormat.format(new Date()) + "  " + cText + "\n");
			}

		} catch (Exception e) {
			if (!strFilename.equals("log.txt")) // to avoid infinite loop when log.txt was closed on shutdown
			{
				log("PluginBase.log(): " + e.getMessage());
			}
		}
	}

	public void log(String strFilename, String strText) {
		log(strFilename, strText, 0);
	}

	public synchronized String getLog(String strFilename) {
		try {

			initLog(strFilename);
			RandomAccessFile file = mLogFiles.get(strFilename);
			file.seek(0);
			StringBuilder buffer;
			buffer = new StringBuilder();
			String cLine;
			while ((cLine = file.readLine()) != null) {
				buffer.append(cLine).append("\n");
			}
			return buffer.toString();

		} catch (IOException e) {
			log("PluginBase.getLog(): " + e.getMessage());
			return null;
		}
	}

	public void clearLog(String strFilename) {
		try {

			initLog(strFilename);
			mLogFiles.get(strFilename).setLength(0);

		} catch (IOException e) {
			log("PluginBase.clearLog(): " + e.getMessage());
		}
	}

	public void clearAllLogs() {
		try {

			for (RandomAccessFile file : mLogFiles.values()) {
				file.setLength(0);
			}

		} catch (IOException e) {
			log("PluginBase.clearAllLogs(): " + e.getMessage());
		}
	}

	public void setLogLevel(int nLevel) throws Exception {
		try {

			setIntProp("loglevel", nLevel);

		} catch (Exception e) {
			throw new Exception("PluginBase.setLogLevel(): " + e.getMessage());
		}
	}

	// standard log
	public void log(String cText) {
		log("log.txt", cText, 0);
	}

	public void log(String cText, int nLogLevel) {
		log("log.txt", cText, nLogLevel);
	}

	protected void clearLog() {
		clearLog("log.txt");
	}

	public String getLog() {
		return getLog("log.txt");
	}

	// methods to set the version of the plugin
	protected void setVersion(String strVersion) {
		this.strVersion = strVersion;
	}

	// methods to add the plugin to the nodes' main menu (fproxy)
	protected void addPluginToMenu(String strMenuTitle, String strMenuTooltip) {
		this.strMenuTitle = strMenuTitle;
		this.strMenuTooltip = strMenuTooltip;
	}

	// methods to add menu items to the plugins menu (fproxy)
	protected void addMenuItem(String strTitle, String strTooltip, String strUri, boolean isFullAccessHostsOnly) {
		try {

			pagemaker.addNavigationLink(getCategory(), strUri, strTitle, strTooltip, isFullAccessHostsOnly, null, this);
			log("item '" + strTitle + "' added to menu");

		} catch (Exception e) {
			log("PluginBase.addMenuItem(): " + e.getMessage());
		}
	}

	// methods to build the plugin
	protected void addPage(PageBase page) {
		try {

			mPages.put(page.getName(), page);

		} catch (Exception e) {
			log("PluginBase.addPage(): " + e.getMessage());
		}
	}

	// methods to set and get persistent properties
	public synchronized void saveProp() {
		try {

			if (prop != null) {
				File file = new File(strPath + "/" + strPropFilename);
				File oldFile = new File(strPath + "/" + strPropFilename + ".old");
				File newFile = new File(strPath + "/" + strPropFilename + ".new");

				if (newFile.exists()) {
					newFile.delete();
				}

				try (FileOutputStream stream = new FileOutputStream(newFile)) {
					prop.store(stream, strTitle);
					stream.flush();
				}
				
				if (oldFile.exists()) {
					oldFile.delete();
				}

				if (file.exists()) {
					file.renameTo(oldFile);
				}

				newFile.renameTo(file);
			}

		} catch (IOException e) {
			log("PluginBase.saveProp(): " + e.getMessage());
		}
	}

	public void setProp(String strKey, String strValue) throws Exception {
		try {

			prop.setProperty(strKey, strValue);

		} catch (Exception e) {
			throw new Exception("PluginBase.setProp(): " + e.getMessage());
		}
	}

	public String getProp(String strKey) throws Exception {
		try {

			return prop.getProperty(strKey);

		} catch (Exception e) {
			throw new Exception("PluginBase.getProp(): " + e.getMessage());
		}
	}

	public void setIntProp(String strKey, int nValue) throws Exception {
		try {

			prop.setProperty(strKey, String.valueOf(nValue));

		} catch (Exception e) {
			throw new Exception("PluginBase.setIntProp(): " + e.getMessage());
		}
	}

	public int getIntProp(String strKey) throws Exception {
		try {

			String strValue = getProp(strKey);
			if (strValue != null && !strValue.equals("")) {
				return Integer.parseInt(strValue);
			} else {
				return 0;
			}

		} catch (Exception e) {
			throw new Exception("PluginBase.getIntProp(): " + e.getMessage());
		}
	}

	protected void removeProp(String strKey) {
		try {

			prop.remove(strKey);

		} catch (Exception e) {
			log("PluginBase.removeProp(): " + e.getMessage());
		}
	}

	protected void clearProp() {
		prop.clear();
	}

	protected void setTimezoneUTC() {
		dateFormat = new SimpleDateFormat("yyyy.MM.dd_HH.mm_ss");
		dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
	}
}
