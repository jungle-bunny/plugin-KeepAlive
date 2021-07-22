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
package keepalive.service.reinserter;

import freenet.client.*;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata.SplitfileAlgorithm;
import freenet.client.async.ClientBaseCallback;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetState;
import freenet.client.async.ClientRequestSchedulerGroup;
import freenet.client.async.ClientRequester;
import freenet.client.async.GetCompletionCallback;
import freenet.client.async.SplitFileFetcher;
import freenet.client.async.SplitFileSegmentKeys;
import freenet.client.async.StreamGenerator;
import freenet.crypt.HashResult;
import freenet.keys.CHKBlock;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.compress.Compressor;
import freenet.support.compress.Compressor.COMPRESSOR_TYPE;
import freenet.support.io.ArrayBucket;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;

import keepalive.Plugin;
import keepalive.model.Block;
import keepalive.model.Segment;
import keepalive.repository.BlockRepository;
import keepalive.service.net.*;
import org.apache.tools.tar.TarInputStream;

public final class Reinserter extends Thread {

    private final Plugin plugin;
    private final int siteId;
    private final CountDownLatch latch;
    private PluginRespirator pr;
    private long lastActivityTime;
    private HashMap<FreenetURI, Metadata> manifestURIs;
    private HashMap<FreenetURI, Block> blocks;
    private int parsedSegmentId;
    private int parsedBlockId;
    private ArrayList<Segment> segments = new ArrayList<>();

    public Reinserter(Plugin plugin, int siteId, CountDownLatch latch) {
        this.plugin = plugin;
        this.siteId = siteId;
        this.latch = latch;
        this.setName("KeepAlive ReInserter " + siteId);
    }

    @Override
    public void run() {
        try {

            // init
            pr = plugin.pluginContext.pluginRespirator;
            manifestURIs = new HashMap<>();
            blocks = new HashMap<>();
            String uriProp = plugin.getProp("uri_" + siteId);
            plugin.log("start reinserter for site " + uriProp + " (" + siteId + ")", 1);
            plugin.clearLog(plugin.getLogFilename(siteId));
            isActive(true);
            long startedAt = System.currentTimeMillis();
            long timeLeft = TimeUnit.HOURS.toMillis(plugin.getIntProp("single_url_timeslot"));

            FreenetURI uri = new FreenetURI(uriProp);

            // update if USK
            if (uri.isUSK()) {
                FreenetURI newUri = updateUsk(uri);
                if (newUri != null && !newUri.equals(uri)) {
                    String newUriString = newUri.toString();
                    plugin.log("received new uri: " + newUriString, 1);
                    if (plugin.isDuplicate(newUriString)) {
                        plugin.log("remove uri as duplicate: " + newUriString, 1);
                        plugin.removeUri(siteId);
                        return;
                    } else {
                        plugin.setProp("uri_" + siteId, newUri.toString());
                        plugin.setProp("blocks_" + siteId, "?");
                        uri = newUri;
                    }
                }
            }

            // check top block availability
            BlockRepository blockRepository = BlockRepository.getInstance(plugin);
            FreenetURI topBlockUri = Client.normalizeUri(uri.clone());
            if (blockRepository.lastAccessDiff(topBlockUri.toString()) > TimeUnit.DAYS.toMillis(1)) {
                try {
                    Client.fetch(topBlockUri, plugin.getFreenetClient());
                } catch (FetchException e) {
                    log(e.getShortMessage(), 0, 0);
                    try {
                        FreenetURI insertUri = Client.insert(
                                topBlockUri, blockRepository.findOne(topBlockUri.toString()), plugin.getFreenetClient());

                        if (insertUri != null) {
                            if (topBlockUri.equals(insertUri)) {
                                log("Successfully inserted top block: " + insertUri.toString(), 0);
                            } else {
                                log("Top block insertion failed - different uri: " + insertUri.toString(), 0);
                            }
                        } else {
                            log("Top block insertion failed (insertUri = null)", 0);
                        }
                    } catch (InsertException e1) {
                        log(e1.getMessage(), 0, 0);
                    }
                }
                blockRepository.lastAccessUpdate(topBlockUri.toString());
            }

            // register uri
            registerManifestUri(uri, -1);

            // load list of keys (if exists)
            // skip if 1 because the manifest failed to fetch before.
            String numBlocks = plugin.getProp("blocks_" + siteId);
            if (!numBlocks.equals("?") && !numBlocks.equals("1")) {
                log("*** loading list of blocks ***", 0, 0);
                loadBlockUris();
            } else {
                // parse metadata
                log("*** parsing data structure ***", 0, 0);
                parsedSegmentId = -1;
                parsedBlockId = -1;
                while (manifestURIs.size() > 0) {
                    if (isInterrupted()) {
                        return;
                    }

                    if (!isActive()) {
                        plugin.log("Stop after stuck state (metadata)", 0);
                        return;
                    }

                    uri = (FreenetURI) manifestURIs.keySet().toArray()[0];
                    log(uri.toString(), 0);
                    try {
                        parseMetadata(uri, null, 0);
                    } catch (FetchFailedException e) {
                        log(e.getMessage(), 0);
                        return;
                    }
                    manifestURIs.remove(uri);
                }

                if (isInterrupted()) {
                    return;
                }

                saveBlockUris();
                plugin.setIntProp("blocks_" + siteId, blocks.size());
                plugin.saveProp();
            }

            // max segment id
            int maxSegmentId = -1;
            for (Block block : blocks.values()) {
                maxSegmentId = Math.max(maxSegmentId, block.getSegmentId());
            }

            // init reinsertion
            if (plugin.getIntProp("segment_" + siteId) == maxSegmentId) {
                plugin.setIntProp("segment_" + siteId, -1);
            }
            if (plugin.getIntProp("segment_" + siteId) == -1) {

                log("*** starting reinsertion ***", 0, 0);

                // reset success counter
                StringBuilder success = new StringBuilder();
                StringBuilder segmentsSuccess = new StringBuilder();
                for (int i = 0; i <= maxSegmentId; i++) {
                    if (i > 0) success.append(",");

                    success.append("0,0");
                    segmentsSuccess.append("0");
                }
                plugin.setProp("success_" + siteId, success.toString());
                plugin.setProp("success_segments_" + siteId, segmentsSuccess.toString());
                plugin.saveProp();

            } else {

                log("*** continuing reinsertion ***", 0, 0);

                // add dummy segments
                for (int i = 0; i <= plugin.getIntProp("segment_" + siteId); i++) {
                    segments.add(null);
                }

                // reset success counter
                String[] successProp = plugin.getProp("success_" + siteId).split(",");
                for (int i = (plugin.getIntProp("segment_" + siteId) + 1) * 2; i < successProp.length; i++) {
                    successProp[i] = "0";
                }
                saveSuccessToProp(successProp);

            }

            // start reinsertion
            boolean doReinsertions = true;
            timeLeft -= System.currentTimeMillis() - startedAt;
            for (long timeSpent = 0; timeLeft - timeSpent > 0; timeSpent = System.currentTimeMillis() - startedAt, timeLeft -= timeSpent) {
                startedAt = System.currentTimeMillis();

                if (isInterrupted()) {
                    return;
                }

                // next segment
                int segmentSize = 0;
                for (Block block : blocks.values()) {
                    if (block.getSegmentId() == segments.size()) {
                        segmentSize++;
                    }
                }
                if (segmentSize == 0) {
                    break; // ready
                }
                Segment segment = new Segment(this, segments.size(), segmentSize);
                for (Block block : blocks.values()) {
                    if (block.getSegmentId() == segments.size()) {
                        segment.addBlock(block);
                    }
                }
                segments.add(segment);
                log(segment, "*** segment size: " + segment.size(), 0);
                doReinsertions = true;

                // get persistence rate of splitfile segments
                if (segment.size() > 1) {
                    log(segment, "starting availability check for segment (n=" +
                            plugin.getIntProp("splitfile_test_size") + ")", 0);

                    // select prove blocks
                    ArrayList<Block> requestedBlocks = new ArrayList<>();
                    // always fetch exactly the configured number of blocks (or half segment size, whichever is smaller)
                    int splitfileTestSize = Math.min(
                            plugin.getIntProp("splitfile_test_size"),
                            (int) Math.ceil(segmentSize / 2.0));

                    for (int i = 0; requestedBlocks.size() < splitfileTestSize; i++) {
                        if (i == segmentSize) {
                            i = 0;
                        }
                        if ((Math.random() < (splitfileTestSize / (double) segmentSize))
                                && !(requestedBlocks.contains(segment.getBlock(i)))) {
                            // add a block
                            requestedBlocks.add(segment.getBlock(i));
                        }
                    }
                    
                    FetchBlocksResult fetchBlocksResult = fetchBlocks(segment, requestedBlocks);

                    double persistenceRate = fetchBlocksResult.calculatePersistenceRate();
                    if (persistenceRate >= (double) plugin.getIntProp("splitfile_tolerance") / 100) {
                        doReinsertions = false;
                        segment.regFetchSuccess(persistenceRate);
                        updateSegmentStatistic(segment, true);
                        log(segment, "availability of segment ok: " + ((int) (persistenceRate * 100)) +
                                "% (approximated)", 0, 1);
                        checkFinishedSegments();
                        if (plugin.getIntProp("segment_" + siteId) != maxSegmentId) {
                            log(segment, "-> segment not reinserted; moving on will resume on next pass.", 0, 1);
                            break;
                        }
                    } else {
                        log(segment, "<b>availability of segment not ok: " +
                                ((int) (persistenceRate * 100)) + "% (approximated)</b>", 0, 1);
                        log(segment, "-> fetch all available blocks now (n=" + segment.size() + ")", 0, 1);
                    }

                    // get all available blocks and heal the segment
                    if (doReinsertions) {
                        // add the rest of the blocks
                        for (int i = 0; i < segment.size(); i++) {
                            if (!(requestedBlocks.contains(segment.getBlock(i)))) {
                                // add a block
                                requestedBlocks.add(segment.getBlock(i));
                            }
                        }

                        fetchBlocksResult = fetchBlocks(segment, requestedBlocks);

                        persistenceRate = fetchBlocksResult.calculatePersistenceRate();
                        if (persistenceRate >= (double) plugin.getIntProp("splitfile_tolerance") / 100.0) {
                            doReinsertions = false;
                            segment.regFetchSuccess(persistenceRate);
                            updateSegmentStatistic(segment, true);
                            log(segment, "availability of segment ok: " + ((int) (persistenceRate * 100)) +
                                    "% (exact)", 0, 1);
                            checkFinishedSegments();
                            if (plugin.getIntProp("segment_" + siteId) != maxSegmentId) {
                                log(segment, "-> segment not reinserted; moving on will resume on next pass.", 0, 1);
                                break;
                            }
                        } else {
                            log(segment, "<b>availability of segment not ok: " +
                                    ((int) (persistenceRate * 100)) + "% (exact)</b>", 0, 1);
                        }
                    }

                    if (doReinsertions) { // persistenceRate < splitfile tolerance
                        // heal segment

                        // init
                        log(segment, "starting segment healing", 0, 1);
                        byte[][] dataBlocks = new byte[segment.dataSize()][];
                        byte[][] checkBlocks = new byte[segment.checkSize()][];
                        boolean[] dataBlocksPresent = new boolean[dataBlocks.length];
                        boolean[] checkBlocksPresent = new boolean[checkBlocks.length];
                        for (int i = 0; i < dataBlocks.length; i++) {
                            if (segment.getDataBlock(i).isFetchSuccessful()) {
                                dataBlocks[i] = segment.getDataBlock(i).getBucket().toByteArray();
                                dataBlocksPresent[i] = true;
                            } else {
                                dataBlocks[i] = new byte[CHKBlock.DATA_LENGTH];
                                dataBlocksPresent[i] = false;
                            }
                        }
                        for (int i = 0; i < checkBlocks.length; i++) {
                            if (segment.getCheckBlock(i).isFetchSuccessful()) {
                                checkBlocks[i] = segment.getCheckBlock(i).getBucket().toByteArray();
                                checkBlocksPresent[i] = true;
                            } else {
                                checkBlocks[i] = new byte[CHKBlock.DATA_LENGTH];
                                checkBlocksPresent[i] = false;
                            }
                        }

                        // decode
                        FECCodec codec = FECCodec.getInstance(SplitfileAlgorithm.ONION_STANDARD);
                        log(segment, "start decoding", 0, 1);
                        try {
                            codec.decode(dataBlocks, checkBlocks, dataBlocksPresent, checkBlocksPresent, CHKBlock.DATA_LENGTH);
                            log(segment, "-> decoding successful", 1, 2);
                        } catch (Exception e) {
                            log(segment, "<b>segment decoding (FEC) failed, do not reinsert</b>", 1, 2);
                            updateSegmentStatistic(segment, false);
                            segment.setHealingNotPossible(true);
                            checkFinishedSegments();
                            continue;
                        }

                        // encode (= build all data blocks  and check blocks from data blocks)
                        log(segment, "start encoding", 0, 1);
                        try {
                            codec.encode(dataBlocks, checkBlocks, checkBlocksPresent, CHKBlock.DATA_LENGTH);
                            log(segment, "-> encoding successful", 1, 2);
                        } catch (Exception e) {
                            log(segment, "<b>segment encoding (FEC) failed, do not reinsert</b>", 1, 2);
                            updateSegmentStatistic(segment, false);
                            segment.setHealingNotPossible(true);
                            checkFinishedSegments();
                            continue;
                        }

                        // finish
                        for (int i = 0; i < dataBlocks.length; i++) {
                            log(segment, "dataBlock_" + i, dataBlocks[i]);
                            segment.getDataBlock(i).setBucket(new ArrayBucket(dataBlocks[i]));
                        }
                        for (int i = 0; i < checkBlocks.length; i++) {
                            log(segment, "checkBlock_" + i, checkBlocks[i]);
                            segment.getCheckBlock(i).setBucket(new ArrayBucket(checkBlocks[i]));
                        }
                        log(segment, "segment healing (FEC) successful, start with reinsertion", 0, 1);
                        updateSegmentStatistic(segment, true);
                    }
                }

                // start reinsertion
                if (doReinsertions) {
                    insertBlocks(segment);
                }

                // check if segments are finished
                checkFinishedSegments();
            }

            // wait for finishing top block, if it was fetched.
            if (segments.size() > 0 && segments.get(0) != null) {
                while (!(segments.get(0).isFinished())) {
                    synchronized (this) {
                        try {
                            this.wait(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    if (isInterrupted()) {
                        return;
                    }

                    if (!isActive()) {
                        plugin.log("Stop after stuck state (wait for finishing top block)", 0);
                        return;
                    }

                    checkFinishedSegments();
                }
            }

            // wait for finishing all segments
            if (doReinsertions) {
                while (plugin.getIntProp("segment_" + siteId) != maxSegmentId) {
                    synchronized (this) {
                        try {
                            this.wait(1_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }

                    if (isInterrupted()) {
                        return;
                    }

                    if (!isActive()) { // TODO: this is a bypass
                        plugin.log("Stop after stuck state (after healing, prop segment_" + siteId + "=" +
                                plugin.getIntProp("segment_" + siteId) + ", maxSegmentId=" + maxSegmentId + ")", 0);
                        // TODO: probably segment_siteId prop should be incremented (switched to next segment)
                        return;
                    }

                    checkFinishedSegments();
                }
            }

            // add to history if we've processed the last segment in the file.
            if (plugin.getIntProp("blocks_" + siteId) > 0
                    && plugin.getIntProp("segment_" + siteId) == maxSegmentId) {
                int nPersistence = (int) ((double) plugin.getSuccessValues(siteId)[0]
                        / plugin.getIntProp("blocks_" + siteId) * 100);
                String cHistory = plugin.getProp("history_" + siteId);
                String[] aHistory;
                if (cHistory == null) {
                    aHistory = new String[]{};
                } else {
                    aHistory = cHistory.split(",");
                }
                String cThisMonth = (new SimpleDateFormat("MM.yyyy")).format(new Date());
                boolean bNewMonth = true;
                if (cHistory != null && cHistory.contains(cThisMonth)) {
                    bNewMonth = false;
                    int nOldPersistence = Integer.parseInt(aHistory[aHistory.length - 1].split("-")[1]);
                    nPersistence = Math.min(nPersistence, nOldPersistence);
                    aHistory[aHistory.length - 1] = cThisMonth + "-" + nPersistence;
                }
                StringBuilder buf = new StringBuilder();
                for (String aHistory1 : aHistory) {
                    if (buf.length() > 0) {
                        buf.append(",");
                    }
                    buf.append(aHistory1);
                }
                if (bNewMonth) {
                    if (cHistory != null && cHistory.length() > 0) {
                        buf.append(",");
                    }
                    buf.append(cThisMonth).append("-").append(nPersistence);
                }
                cHistory = buf.toString();
                plugin.setProp("history_" + siteId, cHistory);
                plugin.saveProp();
            }

            log("*** reinsertion finished ***", 0, 0);
            plugin.log("reinsertion finished for " + plugin.getProp("uri_" + siteId), 1);

        } catch (Exception e) {
            plugin.log("Reinserter.run()", e);
        } finally {
            latch.countDown();
            log("stopped", 0);
            plugin.log("reinserter stopped (" + siteId + ")");
        }
    }
    
    private FetchBlocksResult fetchBlocks(Segment segment, ArrayList<Block> requestedBlocks) throws ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(plugin.getIntProp("power"));
        FetchBlocksResult fetchBlocksResult = new FetchBlocksResult();
        try {
            List<Future<Boolean>> fetchFutures = new ArrayList<Future<Boolean>>();
            for (Block requestedBlock : requestedBlocks) {
                // fetch next block that has not been fetched yet
                if (!requestedBlock.isFetchInProgress()) {
                    continue;
                }
                SingleFetch singleFetch = new SingleFetch(this, requestedBlock, true);
                Future<Boolean> fetchFuture = executor.submit(singleFetch);
                fetchFutures.add(fetchFuture);
            }
            while (fetchFutures.size() > 0) {
                Boolean success = false;
                for (Future<Boolean> fetchFuture : fetchFutures) {
                    if (fetchFuture.isDone()) {
                        fetchBlocksResult.addResult(fetchFuture.get());
                        fetchFutures.remove(fetchFuture);
                        success = true;

                        int finished = fetchBlocksResult.failed+fetchBlocksResult.successful;
                        int logInterval = Math.max(1, requestedBlocks.size() / 8);
                        if (finished % logInterval == 0) {
                            String log = (finished) + "/" + String.valueOf(requestedBlocks.size()) +
                                    " blocks fetched (" + (fetchBlocksResult.successful) + "/" +
                                    (fetchBlocksResult.failed) + ", " +
                                    ((int) (fetchBlocksResult.calculatePersistenceRate() * 100)) + "%)";
                            log(segment.getId(), log, 1, 1);
                        }
                        break;
                    }
                }
                if (!success) {
                    Thread.sleep(1000);
                }
            }
            executor.shutdown();
            boolean done = executor.awaitTermination(1, TimeUnit.HOURS);
            if (!done) {
                log(segment, "<b>fetchBlocks failed</b>", 0);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
        return fetchBlocksResult;
    }

    private void insertBlocks(Segment segment) {
        log(segment, "starting reinsertion", 0, 1);
        segment.initInsert();

        ExecutorService executor = Executors.newFixedThreadPool(plugin.getIntProp("power"));
        try {
            List<Future<?>> insertFutures = new ArrayList<Future<?>>();
            for (int i = 0; i < segment.size(); i++) {
                checkFinishedSegments();
                isActive(true);
                if (segment.size() > 1) {
                    if (segment.getBlock(i).isFetchSuccessful()) {
                        segment.regFetchSuccess(true);
                    } else {
                        segment.regFetchSuccess(false);
                        SingleInsert singleInsert = new SingleInsert(this, segment.getBlock(i));
                        insertFutures.add(executor.submit(singleInsert));
                    }
                } else {
                    SingleInsert singleInsert = new SingleInsert(this, segment.getBlock(i));
                    insertFutures.add(executor.submit(singleInsert));
                }
            }
            int totalInserts = insertFutures.size();
            while (insertFutures.size() > 0) {
                boolean success = false;
                for (Future<?> insertFuture : insertFutures) {
                    if (insertFuture.isDone()) {
                        insertFutures.remove(insertFuture);
                        success = true;

                        int completed = totalInserts - insertFutures.size();
                        int logInterval = Math.max(1, totalInserts / 8);
                        if (completed % logInterval == 0) {
                            String log = (completed) + "/" + String.valueOf(totalInserts) +
                                    " blocks inserted (" + (100 * completed / totalInserts) + "%)";
                            log(segment.getId(), log, 1, 1);
                        }

                        break;
                    }
                }
                if (!success) {
                    Thread.sleep(1000);
                }
            }
            executor.shutdown();
            boolean done = executor.awaitTermination(1, TimeUnit.HOURS);
            if (!done) {
                log(segment, "<b>reinsertion failed</b>", 0);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
    }

    private void checkFinishedSegments() {
        int segment;
        while ((segment = plugin.getIntProp("segment_" + siteId)) < segments.size() - 1) {
            if (segments.get(segment + 1).isFinished()) {
                plugin.setIntProp("segment_" + siteId, segment + 1);
            } else {
                break;
            }
        }
        plugin.saveProp();
    }

    private void saveBlockUris() throws IOException {
        File f = new File(plugin.getPluginDirectory() + plugin.getBlockListFilename(siteId));
        if (f.exists()) {
            if (!f.delete()) {
                log("Reinserter.saveBlockUris(): remove block list log files was not successful.", 0);
            }
        }

        try (RandomAccessFile file = new RandomAccessFile(f, "rw")) {
            file.setLength(0);
            for (Block block : blocks.values()) {
                if (file.getFilePointer() > 0) {
                    file.writeBytes("\n");
                }
                String type = "d";
                if (!block.isDataBlock()) {
                    type = "c";
                }
                file.writeBytes(block.getUri().toString() + "#" + block.getSegmentId() + "#" + block.getId() + "#" + type);
            }
        }
    }

    private synchronized void loadBlockUris() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(
                plugin.getPluginDirectory() + plugin.getBlockListFilename(siteId), "r")) {

            String values;
            while ((values = file.readLine()) != null) {
                String[] aValues = values.split("#");
                FreenetURI uri = new FreenetURI(aValues[0]);
                int segmentId = Integer.parseInt(aValues[1]);
                int blockId = Integer.parseInt(aValues[2]);
                boolean isDataBlock = aValues[3].equals("d");
                blocks.put(uri, new Block(uri, segmentId, blockId, isDataBlock));
            }

        }
    }

    private void parseMetadata(FreenetURI uri, Metadata metadata, int level)
            throws FetchFailedException, MetadataParseException, FetchException, IOException {
        if (isInterrupted()) {
            return;
        }

        // register uri
        registerBlockUri(uri, true, true, level);

        // constructs top level simple manifest (= first action on a new uri)
        if (metadata == null) {
            FetchResult fetchResult = Client.fetch(uri, plugin.getFreenetClient());
            BlockRepository.getInstance(plugin).saveOrUpdate(uri.toString(), fetchResult.asByteArray());

            metadata = fetchManifest(uri, null, null);
            if (metadata == null) {
                log("no metadata", level);
                return;
            }
        }

        // internal manifest (simple manifest)
        if (metadata.isSimpleManifest()) {
            log("manifest (" + getMetadataType(metadata) + "): " + metadata.getResolvedName(), level);
            HashMap<String, Metadata> targetList = null;
            try {
                targetList = metadata.getDocuments();
            } catch (Exception ignored) {
            }

            if (targetList != null) {
                for (Entry<String, Metadata> entry : targetList.entrySet()) {
                    if (isInterrupted()) {
                        return;
                    }
                    // get document
                    Metadata target = entry.getValue();
                    // remember document name
                    target.resolve(entry.getKey());
                    // parse document
                    parseMetadata(uri, target, level + 1);
                }
            }

            return;
        }

        // redirect to submanifest
        if (metadata.isArchiveMetadataRedirect()) {
            log("document (" + getMetadataType(metadata) + "): " + metadata.getResolvedName(), level);
            Metadata subManifest = fetchManifest(uri, metadata.getArchiveType(), metadata.getArchiveInternalName());
            parseMetadata(uri, subManifest, level);
            return;
        }

        // internal redirect
        if (metadata.isArchiveInternalRedirect()) {
            log("document (" + getMetadataType(metadata) + "): " + metadata.getArchiveInternalName(), level);
            return;
        }

        // single file redirect with external key (only possible if archive manifest or simple redirect but not splitfile)
        if (metadata.isSingleFileRedirect()) {
            log("document (" + getMetadataType(metadata) + "): " + metadata.getResolvedName(), level);
            FreenetURI targetUri = metadata.getSingleTarget();
            log("-> redirect to: " + targetUri, level);
            registerManifestUri(targetUri, level);
            registerBlockUri(targetUri, true, true, level);
            return;
        }

        // splitfile
        if (metadata.isSplitfile()) {
            // splitfile type
            if (metadata.isSimpleSplitfile()) {
                log("simple splitfile: " + metadata.getResolvedName(), level);
            } else {
                log("splitfile (not simple): " + metadata.getResolvedName(), level);
            }

            // register blocks
            Metadata metadata2 = (Metadata) metadata.clone();
            SplitFileSegmentKeys[] segmentKeys = metadata2.grabSegmentKeys();
            for (int i = 0; i < segmentKeys.length; i++) {
                int dataBlocks = segmentKeys[i].getDataBlocks();
                int checkBlocks = segmentKeys[i].getCheckBlocks();
                log("segment_" + i + ": " + (dataBlocks + checkBlocks) +
                        " (data=" + dataBlocks + ", check=" + checkBlocks + ")", level + 1);
                for (int j = 0; j < dataBlocks + checkBlocks; j++) {
                    FreenetURI splitUri = segmentKeys[i].getKey(j, null, false).getURI();
                    log("block: " + splitUri, level + 1, 2);
                    registerBlockUri(splitUri, (j == 0), (j < dataBlocks), level + 1);
                }
            }

            // create metadata from splitfile (if not simple splitfile)
            if (!metadata.isSimpleSplitfile()) {
                // TODO: move fetch to net package
                FetchContext fetchContext = pr.getHLSimpleClient().getFetchContext();
                ClientContext clientContext = pr.getNode().clientCore.clientContext;
                FetchWaiter fetchWaiter = new FetchWaiter(plugin.getFreenetClient());
                List<COMPRESSOR_TYPE> decompressors = new LinkedList<>();
                if (metadata.isCompressed()) {
                    log("is compressed: " + metadata.getCompressionCodec(), level + 1);
                    decompressors.add(metadata.getCompressionCodec());
                } else {
                    log("is not compressed", level + 1);
                }
                SplitfileGetCompletionCallback cb = new SplitfileGetCompletionCallback(fetchWaiter);
                VerySimpleGetter vsg = new VerySimpleGetter((short) 2, null, plugin.getFreenetClient());
                SplitFileFetcher sf = new SplitFileFetcher(metadata, cb, vsg,
                        fetchContext, true, decompressors,
                        metadata.getClientMetadata(), 0L, metadata.topDontCompress,
                        metadata.topCompatibilityMode.code, false, metadata.getResolvedURI(),
                        true, clientContext);
                sf.schedule(clientContext);

                // fetchWaiter.waitForCompletion();
                while (cb.getDecompressedData() == null) { // workaround because in some cases fetchWaiter.waitForCompletion() never finished
                    if (isInterrupted()) {
                        return;
                    }

                    if (!isActive()) {
                        throw new FetchFailedException("Manifest cannot be fetched");
                    }

                    synchronized (this) {
                        try {
                            wait(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                sf.cancel(clientContext);
                metadata = fetchManifest(cb.getDecompressedData(), null, null);
                parseMetadata(null, metadata, level + 1);
            }
        }
    }

    private String getMetadataType(Metadata metadata) {
        try {

            String types = "";

            if (metadata.isArchiveManifest()) types += ",AM";

            if (metadata.isSimpleManifest()) types += ",SM";

            if (metadata.isArchiveInternalRedirect()) types += ",AIR";

            if (metadata.isArchiveMetadataRedirect()) types += ",AMR";

            if (metadata.isSymbolicShortlink()) types += ",SSL";

            if (metadata.isSingleFileRedirect()) types += ",SFR";

            if (metadata.isSimpleRedirect()) types += ",SR";

            if (metadata.isMultiLevelMetadata()) types += ",MLM";

            if (metadata.isSplitfile()) types += ",SF";

            if (metadata.isSimpleSplitfile()) types += ",SSF";

            // remove first comma
            if (types.length() > 0) types = types.substring(1);

            return types;

        } catch (Exception e) {
            plugin.log("Reinserter.getMetadataType(): " + e.getMessage());
            return null;
        }
    }

    public Plugin getPlugin() {
        return plugin;
    }

    private static class FetchBlocksResult {

        int successful = 0;
        int failed = 0;

        void addResult(boolean successful) {
            if (successful) {
                this.successful++;
            } else {
                failed++;
            }

        }

        double calculatePersistenceRate() {
            return (double) successful / (successful + failed);
        }
    }

    private class SplitfileGetCompletionCallback implements GetCompletionCallback {

        private final FetchWaiter fetchWaiter;
        private byte[] decompressedSplitFileData = null;

        SplitfileGetCompletionCallback(FetchWaiter fetchWaiter) {
            this.fetchWaiter = fetchWaiter;
        }

        @Override
        public void onFailure(FetchException e, ClientGetState state, ClientContext context) {
            fetchWaiter.onFailure(e, null);
        }

        @Override
        public void onSuccess(StreamGenerator streamGenerator, ClientMetadata clientMetadata,
                              List<? extends Compressor> decompressors,
                              ClientGetState state, ClientContext context) {
            try {

                // get data
                ByteArrayOutputStream rawOutStream = new ByteArrayOutputStream();
                streamGenerator.writeTo(rawOutStream, null);
                rawOutStream.close();
                byte[] compressedSplitFileData = rawOutStream.toByteArray();

                // decompress (if necessary)
                if (decompressors.size() > 0) {
                    try (ByteArrayInputStream compressedInStream = new ByteArrayInputStream(compressedSplitFileData);
                         ByteArrayOutputStream decompressedOutStream = new ByteArrayOutputStream()) {
                        decompressors.get(0).decompress(compressedInStream, decompressedOutStream, Integer.MAX_VALUE, -1);
                        decompressedSplitFileData = decompressedOutStream.toByteArray();
                    }
                    fetchWaiter.onSuccess(null, null);
                } else {
                    decompressedSplitFileData = compressedSplitFileData;
                }

            } catch (IOException e) {
                plugin.log("SplitfileGetCompletionCallback.onSuccess(): " + e.getMessage());
            }
        }

        byte[] getDecompressedData() {
            return decompressedSplitFileData;
        }

        @Override
        public void onBlockSetFinished(ClientGetState state, ClientContext context) {
        }

        @Override
        public void onExpectedMIME(ClientMetadata metadata, ClientContext context) {
        }

        @Override
        public void onExpectedSize(long size, ClientContext context) {
        }

        @Override
        public void onFinalizedMetadata() {
        }

        @Override
        public void onTransition(ClientGetState oldState, ClientGetState newState, ClientContext context) {
        }

        @Override
        public void onExpectedTopSize(long size, long compressed, int blocksReq, int blocksTotal, ClientContext context) {
        }

        @Override
        public void onHashes(HashResult[] hashes, ClientContext context) {
        }

        @Override
        public void onSplitfileCompatibilityMode(CompatibilityMode min, CompatibilityMode max, byte[] customSplitfileKey, boolean compressed, boolean bottomLayer, boolean definitiveAnyway, ClientContext context) {
        }
    }

    private static class VerySimpleGetter extends ClientRequester {

        private final FreenetURI uri;

        VerySimpleGetter(short priorityClass, FreenetURI uri, RequestClient rc) {
            super(priorityClass, rc);
            this.uri = uri;
        }

        @Override
        public ClientRequestSchedulerGroup getSchedulerGroup() {
            return null;
        }

        @Override
        public FreenetURI getURI() {
            return uri;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void onTransition(ClientGetState cgs, ClientGetState cgs1, ClientContext context) {
        }

        @Override
        public void cancel(ClientContext cc) {
        }

        @Override
        public void innerNotifyClients(ClientContext cc) {
        }

        @Override
        protected void innerToNetwork(ClientContext cc) {
        }

        @Override
        protected ClientBaseCallback getCallback() {
            return null;
        }

        private static class FakeCallback implements ClientBaseCallback {

            FakeCallback(RequestClient client) {
                this.client = client;
            }

            final RequestClient client;

            @Override
            public void onResume(ClientContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RequestClient getRequestClient() {
                return client;
            }
        }
    }

    private Metadata fetchManifest(FreenetURI uri, ARCHIVE_TYPE archiveType, String manifestName)
            throws FetchException, IOException {
        FetchResult result = Client.fetch(uri, plugin.getFreenetClient());

        return fetchManifest(result.asByteArray(), archiveType, manifestName);
    }

    private Metadata fetchManifest(byte[] data, ARCHIVE_TYPE archiveType, String manifestName) throws IOException {
        Metadata metadata = null;
        try (ByteArrayInputStream fetchedDataStream = new ByteArrayInputStream(data)) {

            if (manifestName == null) {
                manifestName = ".metadata";
            }

            if (archiveType == null) {
                // try to construct metadata directly
                try {
                    metadata = Metadata.construct(data);
                } catch (MetadataParseException ignored) {
                }
            }

            if (metadata == null) {
                // unzip and construct metadata

                try {

                    InputStream inStream = null;
                    String entryName = null;

                    // get archive stream (try if archive type unknown)
                    if (archiveType == ARCHIVE_TYPE.TAR || archiveType == null) {
                        inStream = new TarInputStream(fetchedDataStream);
                        entryName = ((TarInputStream) inStream).getNextEntry().getName();
                        archiveType = ARCHIVE_TYPE.TAR;
                    }
                    if (archiveType == ARCHIVE_TYPE.ZIP) {
                        inStream = new ZipInputStream(fetchedDataStream);
                        entryName = ((ZipInputStream) inStream).getNextEntry().getName();
                        archiveType = ARCHIVE_TYPE.ZIP;
                    }

                    // construct metadata
                    while (inStream != null && entryName != null) {
                        if (entryName.equals(manifestName)) {
                            byte[] buf = new byte[32768];
                            ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                            int bytes;
                            while ((bytes = inStream.read(buf)) > 0) {
                                outStream.write(buf, 0, bytes);
                            }
                            outStream.close();
                            metadata = Metadata.construct(outStream.toByteArray());
                            break;
                        }
                        if (archiveType == ARCHIVE_TYPE.TAR) {
                            entryName = ((TarInputStream) inStream).getNextEntry().getName();
                        } else {
                            entryName = ((ZipInputStream) inStream).getNextEntry().getName();
                        }
                    }

                } catch (Exception e) {
                    if (archiveType != null)
                        log("unzip and construct metadata: " + e.getMessage(), 0, 2);
                }
            }

            if (metadata != null) {
                if (archiveType != null) {
                    manifestName += " (" + archiveType.name() + ")";
                }
                metadata.resolve(manifestName);
            }
            return metadata;

        }
    }

    private FreenetURI updateUsk(FreenetURI uri) {
        try {
            Client.fetch(uri, plugin.getFreenetClient());
        } catch (freenet.client.FetchException e) {
            if (e.getMode() == FetchException.FetchExceptionMode.PERMANENT_REDIRECT) {
                uri = updateUsk(e.newURI);
            }
        }

        return uri;
    }

    private void registerManifestUri(FreenetURI uri, int level) {
        uri = Client.normalizeUri(uri);
        if (manifestURIs.containsKey(uri)) {
            log("-> already registered manifest", level, 2);
        } else {
            manifestURIs.put(uri, null);
            if (level != -1) {
                log("-> registered manifest", level, 2);
            }
        }
    }

    private void registerBlockUri(FreenetURI uri, boolean newSegment, boolean isDataBlock, int logTabLevel) {
        if (uri != null) { // uri is null if metadata is created from splitfile

            // no reinsertion for SSK but go to sublevel
            if (!uri.isCHK()) {
                log("-> no reinsertion of USK, SSK or KSK", logTabLevel, 2);

                // check if uri already reinserted during this session
            } else if (blocks.containsKey(Client.normalizeUri(uri))) {
                log("-> already registered block", logTabLevel, 2);

                // register
            } else {
                if (newSegment) {
                    parsedSegmentId++;
                    parsedBlockId = -1;
                }
                uri = Client.normalizeUri(uri);
                blocks.put(uri, new Block(uri, parsedSegmentId, ++parsedBlockId, isDataBlock));
                log("-> registered block", logTabLevel, 2);
            }

        }
    }

    public void registerBlockFetchSuccess(Block block) {
        segments.get(block.getSegmentId()).regFetchSuccess(block.isFetchSuccessful());
    }

    public synchronized void updateSegmentStatistic(Segment segment, boolean success) {
        String successProp = plugin.getProp("success_segments_" + siteId);
        if (success) {
            successProp = successProp.substring(0, segment.getId()) + "1" + successProp.substring(segment.getId() + 1);
        }
        plugin.setProp("success_segments_" + siteId, successProp);
        plugin.saveProp();
    }

    public synchronized void updateBlockStatistic(int id, int success, int failed) {
        String[] successProp = plugin.getProp("success_" + siteId).split(",");
        successProp[id * 2] = String.valueOf(success);
        successProp[id * 2 + 1] = String.valueOf(failed);
        saveSuccessToProp(successProp);
    }

    private void saveSuccessToProp(String[] success) {
        StringBuilder newSuccess = new StringBuilder();
        for (int i = 0; i < success.length; i++) {
            if (i > 0) {
                newSuccess.append(",");
            }
            newSuccess.append(success[i]);
        }
        plugin.setProp("success_" + siteId, newSuccess.toString());
        plugin.saveProp();
    }

    public boolean isActive() {
        return isActive(false);
    }

    private boolean isActive(boolean newActivity) {
        if (newActivity) {
            lastActivityTime = System.currentTimeMillis();
            return true;
        }
        if (lastActivityTime != Integer.MIN_VALUE) {
            long delay = (System.currentTimeMillis() - lastActivityTime) / 60_000; // delay in minutes
            return (delay < SingleJob.MAX_LIFETIME + 5);
        }
        return false;
    }

    public void log(int segmentId, String message, int level, int logLevel) {
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < level; i++) {
            buf.append("    ");
        }

        if (segmentId != -1) {
            buf.insert(0, "(" + segmentId + ") ");
        }

        try {
            if (plugin.getIntProp("log_links") == 1) {
                int keyPos = message.indexOf("K@");
                if (keyPos != -1) {
                    keyPos = keyPos - 2;
                    int keyPos2 = Math.max(message.indexOf(" ", keyPos), message.indexOf("<", keyPos));
                    if (keyPos2 == -1) {
                        keyPos2 = message.length();
                    }
                    String key = message.substring(keyPos, keyPos2);
                    message = message.substring(0, keyPos) +
                            "<a href=\"/" + key + "\">" + key + "</a>" + message.substring(keyPos2);
                }
            }
        } catch (Exception ignored) {
        }

        plugin.log(plugin.getLogFilename(siteId), buf.append(message).toString(), logLevel);
    }

    public void log(Segment segment, String message, int level, int logLevel) {
        log(segment.getId(), message, level, logLevel);
    }

    public void log(Segment segment, String message, int level) {
        log(segment, message, level, 1);
    }

    public void log(String message, int level, int logLevel) {
        log(-1, message, level, logLevel);
    }

    public void log(String message, int level) {
        log(-1, message, level, 1);
    }

    public void log(Segment segment, String cMessage, Object obj) {
        if (obj != null) {
            log(segment, cMessage + " = ok", 1, 2);
        } else {
            log(segment, cMessage + " = null", 1, 2);
        }
    }

    public ArrayList<Segment> getSegments() {
        return segments;
    }
}
