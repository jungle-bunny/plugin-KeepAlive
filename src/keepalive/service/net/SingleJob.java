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

public abstract class SingleJob {

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
    }

    FreenetURI getUri() {
        FreenetURI uri = block.getUri().clone();

        // modify the control flag of the URI to get always the raw data
        uriExtra = uri.getExtra();
        uriExtra[2] = 0;

        // get the compression algorithm of the block
        if (uriExtra[4] >= 0) {
            compressionAlgorithm =
                    Compressor.COMPRESSOR_TYPE.getCompressorByMetadataID(uriExtra[4]).name;
        } else {
            compressionAlgorithm = "none";
        }

        log("request: " + block.getUri().toString() +
                " (crypt=" + uriExtra[1] +
                ",control=" + block.getUri().getExtra()[2] +
                ",compress=" + uriExtra[4] + "=" + compressionAlgorithm + ")", 2);

        return uri;
    }

    void finish() {
        if (reinserter.isActive() && !reinserter.isInterrupted()) {
            // log
            String firstLog = jobType + ": " + block.getUri();
            if (!block.isFetchSuccessful() && !block.isInsertSuccessful()) {
                firstLog = "<b>" + firstLog + "</b>";
                block.setResultLog("<b>" + block.getResultLog() + "</b>");
            }
            log(firstLog);
            log(block.getResultLog());
        }
    }

    protected void log(String message, int logLevel) {
        if (reinserter.isActive() && !Thread.currentThread().isInterrupted() && !reinserter.isInterrupted()) {
            reinserter.log(block.getSegmentId(), message, 0, logLevel);
        }
    }

    protected void log(String message) {
        log(message, 1);
    }
}
