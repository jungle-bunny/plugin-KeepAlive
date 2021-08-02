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
package keepalive.web;

import freenet.keys.FreenetURI;

import java.net.MalformedURLException;
import java.net.URLDecoder;

import keepalive.Plugin;
import pluginbase.PageBase;

public class AdminPage extends PageBase {

    private Plugin plugin;

    private final String formPassword;

    public AdminPage(Plugin plugin, String formPassword) {
        super("", "Keep Alive", plugin, true);
        this.plugin = plugin;
        this.formPassword = formPassword;
        addPageToMenu("Start reinsertion of sites", "Add or remove sites you like to reinsert");
    }

    @Override
    protected void handleRequest() {
        try {
            if (formPassword.equals(getParam("formPassword"))) {
                // start reinserter
                if (getParam("start") != null) {
                    plugin.startReinserter(getIntParam("start"));
                }

                // stop reinserter
                if (getParam("stop") != null) {
                    plugin.stopReinserter();
                }

                // modify power
                if (getParam("modify_power") != null) {
                    setIntPropByParam("power", 1);
                    saveProp();
                }

                // modify splitfile tolerance
                if (getParam("splitfile_tolerance") != null) {
                    setIntPropByParam("splitfile_tolerance", 0);
                    saveProp();
                }

                // modify splitfile tolerance
                if (getParam("splitfile_test_size") != null) {
                    setIntPropByParam("splitfile_test_size", 10);
                    saveProp();
                }

                // modify timeslot to heal single url
                if (getParam("single_url_timeslot2") != null) {
                    setIntPropByParam("single_url_timeslot2", 1);
                    saveProp();
                }

                // modify log level
                if (getParam("modify_loglevel") != null || getParam("show_log") != null) {
                    setIntPropByParam("loglevel", 0);
                    saveProp();
                }

                // clear logs
                if (getParam("clear_logs") != null) {
                    plugin.clearAllLogs();
                }

                // clear history
                if (getParam("clear_history") != null) {
                    removeProp("history_" + getParam("clear_history"));
                }

                // add uris
                if (getParam("uris") != null) {
                    addUris();
                }

                // remove uri
                if (getParam("remove") != null) {
                    removeUri();
                }
            }

            // boxes
            int[] ids = plugin.getIds();
            unsupportedKeysBox(ids);
            sitesBox(ids);
            logBox();
            configurationBox();
            historyBox(ids);

            // info box
            addBox("Information",
                    html("info", formPassword).replaceAll("#1", plugin.getVersion()), "page-kp-info");

        } catch (Exception e) {
            log("AdminPage.handleRequest(): " + e.getMessage());
        }
    }

    private void historyBox(int[] ids) throws Exception {
        StringBuilder html = new StringBuilder("<table>");
        for (int id : ids) {
            html.append("<tr><td>")
                    .append(getShortUri(id))
                    .append("</td><td>");

            if (getProp("history_" + id) != null) {
                html.append(getProp("history_" + id)
                        .replaceAll("-", "=")
                        .replaceAll(",", "%, "))
                        .append("%");
            }

            html.append("</td><td><a href=\"?clear_history=")
                    .append(id)
                    .append("&formPassword=")
                    .append(formPassword)
                    .append("\">clear</a></td></tr>");
        }
        html.append("</table>");
        addBox("Lowest rate of blocks availability (monthly)", html.toString(), "page-kp-rate");
    }

    private void configurationBox() throws Exception {
        StringBuilder html = new StringBuilder(html("properties", formPassword));
        html = new StringBuilder(html.toString().replaceAll("#1", getProp("power")));
        html = new StringBuilder(html.toString().replaceAll("#2", getProp("loglevel")));
        html = new StringBuilder(html.toString().replaceAll("#3", getProp("splitfile_tolerance")));
        html = new StringBuilder(html.toString().replaceAll("#4", getProp("splitfile_test_size")));
        html = new StringBuilder(html.toString().replaceAll("#5", getProp("single_url_timeslot2")));
        addBox("Configuration", html.toString(), "page-kp-config");
    }

    private void logBox() throws Exception {
        if (getParam("master_log") != null || getParam("log") != null) {
            String log;
            if (getParam("master_log") != null) {
                log = plugin.getLog();
            } else {
                log = plugin.getLog(plugin.getLogFilename(getIntParam("log")));
            }

            if (log == null) {
                log = "";
            }

            StringBuilder html = new StringBuilder(
                    ("<small>" + log + "</small>")
                            .replaceAll("\n", "<br>")
                            .replaceAll(" {2}", "&nbsp; &nbsp; "));

            if (getParam("master_log") != null) {
                addBox("Master log", html.toString(), null);
            } else {
                addBox("Log for " + getShortUri(getIntParam("log")), html.toString(), null);
            }
        }
    }

    private void sitesBox(int[] ids) throws Exception {
        StringBuilder html = new StringBuilder(html("add_key", formPassword))
                .append("<br><table><tr style=\"text-align:center;\">")
                .append("<td>URI</td><td>total<br>blocks</td>")
                .append("<td>available<br>blocks</td><td>missed<br>blocks</td>")
                .append("<td>blocks<br>availability</td><td>segments<br>availability</td>")
                .append("<td colspan='4'>Actions</td>")
                .append("</tr>");

        for (int id : ids) {
            String uri = getProp("uri_" + id);
            int success = plugin.getSuccessValues(id)[0];
            int failure = plugin.getSuccessValues(id)[1];

            int persistence = 0;
            if (success > 0) {
                persistence = (int) ((double) success / (success + failure) * 100);
            }

            int availableSegments = plugin.getSuccessValues(id)[2];
            int finishedSegmentsCount = getIntProp("segment_" + id) + 1;

            int segmentsAvailability = 0;
            if (finishedSegmentsCount > 0) {
                segmentsAvailability = (int) ((double) availableSegments / finishedSegmentsCount * 100);
            }

            html.append("<tr>" + "<td><a href='/")
                    .append(uri)
                    .append("'>")
                    .append(getShortUri(id))
                    .append("</a></td><td align=\"center\">")
                    .append(getProp("blocks_" + id))
                    .append("</td><td align=\"center\">")
                    .append(success)
                    .append("</td><td align=\"center\">")
                    .append(failure)
                    .append("</td><td align=\"center\">")
                    .append(persistence)
                    .append(" %</td><td align=\"center\">")
                    .append(segmentsAvailability)
                    .append(" %</td><td><a href='?remove=")
                    .append(id)
                    .append("&formPassword=")
                    .append(formPassword)
                    .append("'>remove</a></td><td><a href='?log=")
                    .append(id)
                    .append("&formPassword=")
                    .append(formPassword)
                    .append("'>log</a></td>");

            if (id == getIntProp("active")) {
                html.append("<td><a href='?stop=")
                        .append(id)
                        .append("&formPassword=")
                        .append(formPassword)
                        .append("'>stop</a></td><td><b>active</b></td>");
            } else {
                html.append("<td><a href='?start=")
                        .append(id)
                        .append("&formPassword=")
                        .append(formPassword)
                        .append("'>start</a></td><td></td>");
            }

            html.append("</tr>");
        }

        html.append("</table>");
        addBox("Add or remove a key", html.toString(), "page-kp-keys");
    }

    private void unsupportedKeysBox(int[] ids) throws Exception {
        StringBuilder zeroBlockSites = new StringBuilder();
        for (int id : ids) {
            if (getProp("blocks_" + id).equals("0")) {
                if (zeroBlockSites.length() > 0) {
                    zeroBlockSites.append("<br>");
                }
                zeroBlockSites.append(getProp("uri_" + id));
            }
        }

        if (zeroBlockSites.length() > 0) {
            addBox("Unsupported keys",
                    html("unsupported_keys", formPassword).replaceAll("#", zeroBlockSites.toString()), null);
        }
    }

    private void addUris() throws Exception {
        for (String splitURI : getParam("uris").split("\n")) {
            // validate
            String uriOrig = URLDecoder.decode(splitURI, "UTF8").trim();
            if (uriOrig.equals("")) {
                continue;  //ignore blank lines.
            }

            String uri = uriOrig;
            int begin = uri.indexOf("@") - 3;
            if (begin > 0) {
                uri = uri.substring(begin);
            }

            try {
                uri = new FreenetURI(uri).toString();

                // add if not already on the list
                if (!isDuplicate(uri)) {
                    int[] ids = plugin.getIds();

                    int id;
                    if (ids.length == 0) {
                        id = 0;
                    } else {
                        id = ids[ids.length - 1] + 1;
                    }

                    setProp("ids", getProp("ids") + id + ",");
                    setProp("uri_" + id, uri);
                    setProp("blocks_" + id, "?");
                    setProp("success_" + id, "");
                    setIntProp("segment_" + id, -1);
                }
            } catch (MalformedURLException e) {
                addBox("URI not valid!", "You have typed:<br><br>" + uriOrig, null);
            }
        }
    }

    private void removeUri() throws Exception {
        plugin.removeUri(getIntParam("remove"));
    }

    // TODO
    protected synchronized void updateUskEdition(int siteId) {
        try {

            String siteUri = plugin.getProp("uri_" + siteId);
            String id = "updateUskEdition" + System.currentTimeMillis();
            fcp.sendClientGet(id, siteUri);

            for (int secs = 0; secs < 300 && getMessage(id, "AllData") == null; secs++) {
                wait(1_000);
            }

            if (getRedirectURI() != null) {
                plugin.setProp("uri_" + siteId, getRedirectURI());
                log("RedirectURI: " + getRedirectURI(), 1);
            }

        } catch (Exception e) {
            log("AdminPage.updateUskEdition(): " + e.getMessage());
        }
    }

    private String getShortUri(int siteId) {
        try {

            String uri = getProp("uri_" + siteId);
            if (uri.length() > 80) {
                return uri.substring(0, 20) + "...." + uri.substring(uri.length() - 50);
            } else {
                return uri;
            }

        } catch (Exception e) {
            log("AdminPage.getShortUri(): " + e.getMessage());
            return null;
        }
    }

    private void setIntPropByParam(String cPropName, int nMinValue) {
        try {

            int value = nMinValue;

            try {
                value = getIntParam(cPropName);
            } catch (Exception ignored) {
            }

            if (value != -1 && value < nMinValue) {
                value = nMinValue;
            }

            setIntProp(cPropName, value);
            saveProp();

        } catch (Exception e) {
            log("AdminPage.setPropByParam(): " + e.getMessage());
        }
    }

    private boolean isDuplicate(String uri) {
        boolean isDuplicate = plugin.isDuplicate(uri);

        if (isDuplicate) {
            addBox("Duplicate URI", "We are already keeping this key alive:<br><br>" + uri, null);
        }

        return isDuplicate;
    }
}
