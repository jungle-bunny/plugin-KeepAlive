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
package keepalive.model;

import freenet.keys.FreenetURI;
import freenet.support.io.ArrayBucket;

public class Block {

    private int id;
    private int segmentId;
    private FreenetURI uri;
    private ArrayBucket bucket;
    private boolean dataBlock;
    private boolean fetchDone; // done but not necessarily successful
    private boolean fetchSuccessful;
    private boolean insertDone; // done but not necessarily successful
    private boolean insertSuccessful;
    private String resultLog;

    public Block(FreenetURI uri, int segmentId, int id, boolean isDataBlock) {
        this.id = id;
        this.segmentId = segmentId;
        this.uri = uri;
        dataBlock = isDataBlock;
    }

    public int getId() {
        return id;
    }

    public int getSegmentId() {
        return segmentId;
    }

    public FreenetURI getUri() {
        return uri;
    }

    public ArrayBucket getBucket() {
        return bucket;
    }

    public void setBucket(ArrayBucket bucket) {
        this.bucket = bucket;
    }

    public boolean isDataBlock() {
        return dataBlock;
    }

    public boolean isFetchInProgress() {
        return !fetchDone;
    }

    public void setFetchDone(boolean done) {
        fetchDone = done;
    }

    boolean isInsertDone() {
        return insertDone;
    }

    public void setInsertDone(boolean done) {
        insertDone = done;
    }

    public boolean isInsertSuccessful() {
        return insertSuccessful;
    }

    public void setInsertSuccessful(boolean successful) {
        insertSuccessful = successful;
    }

    public boolean isFetchSuccessful() {
        return fetchSuccessful;
    }

    public void setFetchSuccessful(boolean successful) {
        fetchSuccessful = successful;
    }

    public String getResultLog() {
        return resultLog;
    }

    public void setResultLog(String result) {
        resultLog = result;
    }

    public void appendResultLog(String result) {
        resultLog += result;
    }
}
