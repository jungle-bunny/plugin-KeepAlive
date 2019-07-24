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
package keepalive.service.net;

import freenet.keys.FreenetURI;
import freenet.support.compress.Compressor;
import keepalive.Plugin;
import keepalive.service.reinserter.Reinserter;
import keepalive.model.Block;

public abstract class SingleJob extends Thread {

    public static final int MAX_LIFETIME = 30;

    Plugin plugin;
    Reinserter reinserter;
    Block block;
    byte[] uriExtra;
    String compressionAlgorithm;

    private String jobType;

    SingleJob(Reinserter reinserter, String jobType, Block block) {
        this.reinserter = reinserter;
        this.jobType = jobType;
        this.block = block;
        this.plugin = reinserter.getPlugin();
        this.setName("KeepAlive SingleJob");

        // init
        reinserter.incrementActiveSingleJobCount();
    }

    @Override
    public void run() {
        try {

            // start lifetime guard
            (new ActivityGuard(this, jobType)).start();

        } catch (Exception e) {
            plugin.log("singleJob.run(): " + e.getMessage(), 0);
        }
    }

    FreenetURI getUri() {
        FreenetURI uri = block.getUri().clone();

        // modify the control flag of the URI to get always the raw data
        uriExtra = uri.getExtra();
        uriExtra[2] = 0;

        // get the compression algorithm of the block
        if (uriExtra[4] >= 0)
            compressionAlgorithm =
                    Compressor.COMPRESSOR_TYPE.getCompressorByMetadataID((short) uriExtra[4]).name;
        else
            compressionAlgorithm = "none";

        log("request: " + block.getUri().toString() +
                " (crypt=" + uriExtra[1] +
                ",control=" + block.getUri().getExtra()[2] +
                ",compress=" + uriExtra[4] + "=" + compressionAlgorithm + ")", 2);

        return uri;
    }

    void finish() {
        try {

            if (reinserter.isActive()) {
                // log
                String cFirstLog = jobType + ": " + block.getUri();
                if (!block.isFetchSuccessful() && !block.isInsertSuccessful()) {
                    cFirstLog = "<b>" + cFirstLog + "</b>";
                    block.setResultLog("<b>" + block.getResultLog() + "</b>");
                }
                log(cFirstLog);
                log(block.getResultLog());

                // finish
                reinserter.decrementActiveSingleJobCount();
            }

        } catch (Exception e) {
            plugin.log("singleJob.finish(): " + e.getMessage(), 0);
        }
    }

    protected void log(String message, int logLevel) {
        if (reinserter.isActive()) {
            reinserter.log(block.getSegmentId(), message, 0, logLevel);
        }
    }

    protected void log(String message) {
        log(message, 1);
    }

    private class ActivityGuard extends Thread {

        private final SingleJob singleJob;
        private final long startTime;
        private final String type;

        ActivityGuard(SingleJob singleJob, String type) {
            this.singleJob = singleJob;
            this.type = type;
            startTime = System.currentTimeMillis();
        }

        @Override
        public synchronized void run() {
            try {

                // stop
                while (reinserter.isActive() && singleJob.isAlive() && getLifetime() < MAX_LIFETIME) {
                    wait(1000);
                }

                // has timeout stop
                if (reinserter.isActive() && singleJob.isAlive()) {
                    singleJob.stop();
                    singleJob.block.appendResultLog("<b>-> " + jobType + " aborted (timeout)</b>");
                }

                // has stopped after reinserter stop
                if (!reinserter.isActive()) {
                    singleJob.stop();
                    long nStopCheckBegin = System.currentTimeMillis();
                    while (singleJob.isAlive() && nStopCheckBegin > System.currentTimeMillis() - 60 * 1000) {
                        try {
                            wait(1000);
                        } catch (InterruptedException ignored) {
                        }
                    }

                    if (!singleJob.isAlive()) {
                        plugin.log("single " + type + " stopped (" + singleJob.reinserter.getSiteId() + ")");
                    } else {
                        plugin.log("single " + type +
                                " not stopped  - stop was indicated 1 minute before (" +
                                singleJob.reinserter.getSiteId() + ")");
                    }
                }

            } catch (InterruptedException e) {
                plugin.log("singleJob.ActivityGuard.run(): " + e.getMessage(), 0);
            }
        }

        private long getLifetime() {
            return (System.currentTimeMillis() - startTime) / 60 / 1000;
        }
    }
}
