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

import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.ClientMetadata;
import freenet.client.FECCodec;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchException.FetchExceptionMode;
import freenet.client.FetchResult;
import freenet.client.FetchWaiter;
import freenet.client.InsertContext.CompatibilityMode;
import freenet.client.Metadata;
import freenet.client.Metadata.SplitfileAlgorithm;
import freenet.client.MetadataParseException;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.zip.ZipInputStream;

import keepalive.Plugin;
import keepalive.model.Block;
import keepalive.model.Segment;
import keepalive.service.net.SingleFetch;
import keepalive.service.net.SingleInsert;
import keepalive.service.net.SingleJob;
import keepalive.util.Debug;
import org.apache.tools.tar.TarInputStream;

public class Reinserter extends Thread {

	private Plugin plugin;
	private PluginRespirator pr;
	private int siteId;
	private long lastActivityTime;
	private HashMap<FreenetURI, Metadata> manifestURIs;
	private HashMap<FreenetURI, Block> blocks;
	private int parsedSegmentId;
	private int parsedBlockId;
	private ArrayList<Segment> segments = new ArrayList<>();
	private int activeSingleJobCount = 0;

	private RequestClient rc = new RequestClient() {

		@Override
		public boolean persistent() {
			return false;
		}

		@Override
		public boolean realTimeFlag() {
			return true;
		}
	};

	public Reinserter(Plugin plugin, int siteId) {
		try {

			this.plugin = plugin;
			this.siteId = siteId;
			this.setName("KeepAlive ReInserter");

			// stop previous reinserter, start this one
			plugin.stopReinserter();
			plugin.setIntProp("active", siteId);
			plugin.saveProp();
			plugin.setReinserter(this);

			// activity guard
			(new ActivityGuard(this)).start();

		} catch (Exception e) {
			plugin.log("Reinserter(): " + e.getMessage());
		}
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

			// update and register uri
			FreenetURI uri = new FreenetURI(uriProp);
			if (uri.isUSK()) {
				FreenetURI newUri = updateUsk(uri);
				if (newUri != null && !newUri.equals(uri)) {
					plugin.setProp("uri_" + siteId, newUri.toString());
					plugin.setProp("blocks_" + siteId, "?");
					uri = newUri;
				}
			}
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
					if (!isActive()) return;

					uri = (FreenetURI) manifestURIs.keySet().toArray()[0];
					log(uri.toString(), 0);
					parseMetadata(uri, null, 0);
					manifestURIs.remove(uri);

				}

				if (!isActive()) return;

				saveBlockUris();
				plugin.setIntProp("blocks_" + siteId, blocks.size());
				plugin.saveProp();
			}

			// max segment id
			int maxSegmentId = -1;
			for (Block block : blocks.values())
				maxSegmentId = Math.max(maxSegmentId, block.getSegmentId());

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
			int power = plugin.getIntProp("power");
			boolean doReinsertions = true;
			while (true) {
				if (!isActive()) {
					return;
				}

				// next segment
				int nSegmentSize = 0;
				for (Block block : blocks.values()) {
					if (block.getSegmentId() == segments.size()) {
						nSegmentSize++;
					}
				}
				if (nSegmentSize == 0) {
					break; // ready
				}
				Segment segment = new Segment(this, segments.size(), nSegmentSize);
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
					int segmentSize = segment.size();
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

					for (Block requestedBlock : requestedBlocks) {
						waitForNextFreeThread(power);

						// fetch a block
						(new SingleFetch(this, requestedBlock, true)).start();
					}

					FetchBlocksResult result = waitForAllBlocksFetched(requestedBlocks);

					// todo: duplicate code
					// calculate persistence rate
					double persistenceRate = (double) result.successful / (result.successful + result.failed);
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
						log(segment, "-> fetch all available blocks now", 0, 1);
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
						for (Block vRequestedBlock : requestedBlocks) {
							waitForNextFreeThread(power);

							// fetch next block that has not been fetched yet
							if (vRequestedBlock.isFetchInProcess()) {
								SingleFetch fetch = new SingleFetch(this, vRequestedBlock, true);
								fetch.start();
							}
						}

						result = waitForAllBlocksFetched(requestedBlocks);

						// todo: duplicate code
						// calculate persistence rate
						persistenceRate = (double) result.successful / (result.successful + result.failed);
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
					if (doReinsertions) {

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

					log(segment, "starting reinsertion", 0, 1);
					segment.initInsert();

					for (int i = 0; i < segment.size(); i++) {
						while (activeSingleJobCount >= plugin.getIntProp("power")) {
							synchronized (this) {
								this.wait(1000);
							}
							if (!isActive()) {
								return;
							}
						}
						checkFinishedSegments();
						isActive(true);
						if (segment.size() > 1) {
							if (segment.getBlock(i).isFetchSuccessful()) {
								segment.regFetchSuccess(true);
							} else {
								segment.regFetchSuccess(false);
								(new SingleInsert(this, segment.getBlock(i))).start();
							}
						} else {
							(new SingleInsert(this, segment.getBlock(i))).start();
						}
					}

				}

				// check if segments are finished
				checkFinishedSegments();
			}

			// wait for finishing top block, if it was fetched.
			if (segments.get(0) != null) {
				while (!(segments.get(0).isFinished())) {
					synchronized (this) {
						this.wait(1000);
					}
					if (!isActive()) {
						return;
					}
					checkFinishedSegments();
				}
			}

			// TODO: doReinsertions is not updated inside loop
			// wait for finishing all segments
			while (doReinsertions) {
				if (plugin.getIntProp("segment_" + siteId) == maxSegmentId) {
					break;
				}
				synchronized (this) {
					this.wait(1000);
				}
				if (!isActive()) {
					return;
				}
				checkFinishedSegments();
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
					int nOldPersistence = Integer.valueOf(aHistory[aHistory.length - 1].split("-")[1]);
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

			// start reinsertion of next site
			log("*** reinsertion finished ***", 0, 0);
			plugin.log("reinsertion finished for " + plugin.getProp("uri_" + siteId), 1);
			int[] aIds = plugin.getIds();
			int i = -1;
			for (int j = 0; j < aIds.length; j++) {
				i = j;
				if (siteId == aIds[j]) {
					break;
				}
			}
			if (!isActive()) {
				return;
			}
			if (i < aIds.length - 1) {
				plugin.startReinserter(aIds[i + 1]);
			} else {
				plugin.startReinserter(aIds[0]);
			}

		} catch (Exception e) {
			plugin.log("Reinserter.run(): " + e.getMessage(), 0);
		}
	}

	private FetchBlocksResult waitForAllBlocksFetched(List<Block> requestedBlocks) throws InterruptedException {
		FetchBlocksResult result = new FetchBlocksResult();
		for (Block vRequestedBlock : requestedBlocks) {
			while (vRequestedBlock.isFetchInProcess()) {
				synchronized (this) {
					this.wait(1000);
				}
				if (!isActive()) {
					return result;
				}
			}
			checkFinishedSegments();
			isActive(true);
			if (vRequestedBlock.isFetchSuccessful()) {
				result.successful++;
			} else {
				result.failed++;
			}
		}
		return result;
	}

	private void waitForNextFreeThread(int power) throws InterruptedException {
		while (activeSingleJobCount >= power) {
			synchronized (this) {
				this.wait(1000);
			}
			if (!isActive()) {
				return;
			}
		}
		checkFinishedSegments();
		isActive(true);
	}

	private void checkFinishedSegments() {
		try {

			int nSegment;
			while ((nSegment = plugin.getIntProp("segment_" + siteId)) < segments.size() - 1) {
				if (segments.get(nSegment + 1).isFinished()) {
					plugin.setIntProp("segment_" + siteId, nSegment + 1);
				} else {
					break;
				}
			}
			plugin.saveProp();

		} catch (Exception e) {
			plugin.log("Reinserter.checkFinishedSegments(): " + e.getMessage(), 0);
		}
	}

	private void saveBlockUris() {
		try {

			File f = new File(plugin.getPluginDirectory() + plugin.getBlockListFilename(siteId));
			if (f.exists()) {
				if (!f.delete())
					log("Reinserter.saveBlockUris(): remove block list log files was not successful.", 0);
			}

			try (RandomAccessFile file = new RandomAccessFile(f, "rw")) {
				file.setLength(0);
				for (Block block : blocks.values()) {
					if (file.getFilePointer() > 0) {
						file.writeBytes("\n");
					}
					String cType = "d";
					if (!block.isDataBlock()) {
						cType = "c";
					}
					file.writeBytes(block.getUri().toString() + "#" + block.getSegmentId() + "#" + block.getId() + "#" + cType);
				}
			}

		} catch (IOException e) {
			plugin.log("Reinserter.saveBlockUris(): " + e.getMessage(), 0);
		}
	}

	private synchronized void loadBlockUris() {
		try {

			try (RandomAccessFile file = new RandomAccessFile(
				 plugin.getPluginDirectory() + plugin.getBlockListFilename(siteId), "r")) {
				String cValues;
				while ((cValues = file.readLine()) != null) {
					String[] aValues = cValues.split("#");
					FreenetURI uri = new FreenetURI(aValues[0]);
					int nSegmentId = Integer.parseInt(aValues[1]);
					int nId = Integer.parseInt(aValues[2]);
					boolean bIsDataBlock = aValues[3].equals("d");
					blocks.put(uri, new Block(uri, nSegmentId, nId, bIsDataBlock));
				}
			}

		} catch (IOException | NumberFormatException e) {
			plugin.log("Reinserter.loadBlockUris(): " + e.getMessage(), 0);
		}
	}

	private void parseMetadata(FreenetURI uri, Metadata metadata, int nLevel) {
		try {

			// activity flag
			if (!isActive()) return;

			// register uri
			registerBlockUri(uri, true, true, nLevel);

			// constructs top level simple manifest (= first action on a new uri)
			if (metadata == null) {

				metadata = fetchManifest(uri, null, null);
				if (metadata == null) {
					log("no metadata", nLevel);
					return;
				}

			}

			// internal manifest (simple manifest)
			if (metadata.isSimpleManifest()) {

				log("manifest (" + getMetadataType(metadata) + "): " + metadata.getResolvedName(), nLevel);
				HashMap<String, Metadata> targetList = null;
				try {
					targetList = metadata.getDocuments();
				} catch (Exception ignored) {
				}

				if (targetList != null) {
					for (Entry<String, Metadata> entry : targetList.entrySet()) {
						if (!isActive()) {
							return;
						}
						// get document
						Metadata target = entry.getValue();
						// remember document name
						target.resolve(entry.getKey());
						// parse document
						parseMetadata(uri, target, nLevel + 1);
					}
				}

				return;

			}

			// redirect to submanifest
			if (metadata.isArchiveMetadataRedirect()) {

				log("document (" + getMetadataType(metadata) + "): " + metadata.getResolvedName(), nLevel);
				Metadata subManifest = fetchManifest(uri, metadata.getArchiveType(), metadata.getArchiveInternalName());
				parseMetadata(uri, subManifest, nLevel);
				return;

			}

			// internal redirect
			if (metadata.isArchiveInternalRedirect()) {

				log("document (" + getMetadataType(metadata) + "): " + metadata.getArchiveInternalName(), nLevel);
				return;

			}

			// single file redirect with external key (only possible if archive manifest or simple redirect but not splitfile)
			if (metadata.isSingleFileRedirect()) {

				log("document (" + getMetadataType(metadata) + "): " + metadata.getResolvedName(), nLevel);
				FreenetURI targetUri = metadata.getSingleTarget();
				log("-> redirect to: " + targetUri, nLevel);
				registerManifestUri(targetUri, nLevel);
				registerBlockUri(targetUri, true, true, nLevel);
				return;

			}

			// splitfile
			if (metadata.isSplitfile()) {

				// splitfile type
				if (metadata.isSimpleSplitfile()) {
					log("simple splitfile: " + metadata.getResolvedName(), nLevel);
				} else {
					log("splitfile (not simple): " + metadata.getResolvedName(), nLevel);
				}

				// register blocks
				Metadata metadata2 = (Metadata) metadata.clone();
				SplitFileSegmentKeys[] segmentKeys = metadata2.grabSegmentKeys();
				for (int i = 0; i < segmentKeys.length; i++) {
					int nDataBlocks = segmentKeys[i].getDataBlocks();
					int nCheckBlocks = segmentKeys[i].getCheckBlocks();
					log("segment_" + i + ": " + (nDataBlocks + nCheckBlocks) +
						 " (data=" + nDataBlocks + ", check=" + nCheckBlocks + ")", nLevel + 1);
					for (int j = 0; j < nDataBlocks + nCheckBlocks; j++) {
						FreenetURI splitUri = segmentKeys[i].getKey(j, null, false).getURI();
						log("block: " + splitUri, nLevel + 1);
						registerBlockUri(splitUri, (j == 0), (j < nDataBlocks), nLevel + 1);
					}
				}

				// create metadata from splitfile (if not simple splitfile)
				if (!metadata.isSimpleSplitfile()) {
					FetchContext fetchContext = pr.getHLSimpleClient().getFetchContext();
					freenet.client.async.ClientContext clientContext = pr.getNode().clientCore.clientContext;
					FetchWaiter fetchWaiter = new FetchWaiter(plugin.getFreenetClient());
					List<COMPRESSOR_TYPE> decompressors = new LinkedList<>();
					if (metadata.isCompressed()) {
						log("is compressed: " + metadata.getCompressionCodec(), nLevel + 1);
						decompressors.add(metadata.getCompressionCodec());
					} else {
						log("is not compressed", nLevel + 1);
					}
					SplitfileGetCompletionCallback cb = new SplitfileGetCompletionCallback(fetchWaiter);
					VerySimpleGetter vsg = new VerySimpleGetter((short) 2, null, plugin.getFreenetClient());
					SplitFileFetcher sf = new SplitFileFetcher(metadata, cb, vsg,
						 fetchContext, true, decompressors,
						 metadata.getClientMetadata(), 0L, metadata.topDontCompress,
						 metadata.topCompatibilityMode.code, false, metadata.getResolvedURI(),
						 true, clientContext);
					sf.schedule(clientContext);

					// TODO
					// fetchWaiter.waitForCompletion();
					while (cb.getDecompressedData() == null) { // workaround because in some cases fetchWaiter.waitForCompletion() never finished
						if (!isActive()) return;

						synchronized (this) {
							wait(100);
						}
					}
					sf.cancel(clientContext);
					metadata = fetchManifest(cb.getDecompressedData(), null, null);
					parseMetadata(null, metadata, nLevel + 1);
				}

			}

		} catch (Exception e) {
			plugin.log("Reinserter.parseMetadata(): " + e.getMessage());
		}
	}

	private String getMetadataType(Metadata metadata) {
		try {

			String cTypes = "";

			if (metadata.isArchiveManifest()) cTypes += ",AM";

			if (metadata.isSimpleManifest()) cTypes += ",SM";

			if (metadata.isArchiveInternalRedirect()) cTypes += ",AIR";

			if (metadata.isArchiveMetadataRedirect()) cTypes += ",AMR";

			if (metadata.isSymbolicShortlink()) cTypes += ",SSL";

			if (metadata.isSingleFileRedirect()) cTypes += ",SFR";

			if (metadata.isSimpleRedirect()) cTypes += ",SR";

			if (metadata.isMultiLevelMetadata()) cTypes += ",MLM";

			if (metadata.isSplitfile()) cTypes += ",SF";

			if (metadata.isSimpleSplitfile()) cTypes += ",SSF";

			// remove first comma
			if (cTypes.length() > 0) cTypes = cTypes.substring(1);

			return cTypes;

		} catch (Exception e) {
			plugin.log("Reinserter.getMetadataType(): " + e.getMessage());
			return null;
		}
	}

	public Plugin getPlugin() {
		return plugin;
	}

	public void incrementActiveSingleJobCount() {
		activeSingleJobCount++;
	}

	public void decrementActiveSingleJobCount() {
		activeSingleJobCount--;
	}

	private class FetchBlocksResult {

		int successful = 0;
		int failed = 0;
	}

	private class SplitfileGetCompletionCallback implements GetCompletionCallback {

		private final FetchWaiter fetchWaiter;
		private byte[] aDecompressedSplitFileData = null;

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
				byte[] aCompressedSplitFileData = rawOutStream.toByteArray();

				// decompress (if necessary)
				if (decompressors.size() > 0) {
					ByteArrayOutputStream decompressedOutStream;
					try (ByteArrayInputStream compressedInStream = new ByteArrayInputStream(aCompressedSplitFileData)) {
						decompressedOutStream = new ByteArrayOutputStream();
						decompressors.get(0).decompress(compressedInStream, decompressedOutStream, Integer.MAX_VALUE, -1);
					}
					decompressedOutStream.close();
					aDecompressedSplitFileData = decompressedOutStream.toByteArray();
					fetchWaiter.onSuccess(null, null);
				} else {
					aDecompressedSplitFileData = aCompressedSplitFileData;
				}

			} catch (IOException e) {
				plugin.log("SplitfileGetCompletionCallback.onSuccess(): " + e.getMessage());
			}
		}

		byte[] getDecompressedData() {
			return aDecompressedSplitFileData;
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

		@Override
		public ClientRequestSchedulerGroup getSchedulerGroup() {
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

		VerySimpleGetter(short priorityclass, FreenetURI uri, RequestClient rc) {
			super(priorityclass, new FakeCallback(rc));
			this.uri = uri;
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

	}

	private Metadata fetchManifest(FreenetURI uri, ARCHIVE_TYPE archiveType, String cManifestName) {
		try {

			// init
			uri = normalizeUri(uri);
			if (uri.isCHK())
				uri.getExtra()[2] = 0;  // deactivate control flag

			// fetch raw data
			FetchContext fetchContext = plugin.getFreenetClient().getFetchContext();
			fetchContext.returnZIPManifests = true;
			FetchWaiter fetchWaiter = new FetchWaiter(rc);
			plugin.getFreenetClient().fetch(uri, -1, fetchWaiter, fetchContext);
			FetchResult result = fetchWaiter.waitForCompletion();

			return fetchManifest(result.asByteArray(), archiveType, cManifestName);

		} catch (FetchException | IOException e) {
			plugin.log("Reinserter.fetchManifest(uri): " + e.getMessage());
			return null;
		}
	}

	private Metadata fetchManifest(byte[] aData, ARCHIVE_TYPE archiveType, String cManifestName) {
		Metadata metadata;
		try (
			 ByteArrayInputStream fetchedDataStream = new ByteArrayInputStream(aData)) {

			metadata = null;
			if (cManifestName == null) {
				cManifestName = ".metadata";
			}

			if (archiveType == null) {
				//try to construct metadata directly
				try {
					metadata = Metadata.construct(aData);
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
						if (entryName.equals(cManifestName)) {
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
						log("unzip and construct metadata: " + Debug.stackTrace(e), 0, 2);
				}
			}

			if (metadata != null) {
				if (archiveType != null) {
					cManifestName += " (" + archiveType.name() + ")";
				}
				metadata.resolve(cManifestName);
			}
			return metadata;

		} catch (Exception e) {
			plugin.log("Reinserter.fetchManifest(data): " + e.getMessage());
			return null;
		}
	}

	private FreenetURI updateUsk(FreenetURI uri) {
		FetchContext fetchContext = plugin.getFreenetClient().getFetchContext();
		fetchContext.returnZIPManifests = true;
		FetchWaiter fetchWaiter = new FetchWaiter(rc);

		try {
			plugin.getFreenetClient().fetch(uri, -1, fetchWaiter, fetchContext);
			fetchWaiter.waitForCompletion();
		} catch (freenet.client.FetchException e) {
			if (e.getMode() == FetchExceptionMode.PERMANENT_REDIRECT) {
				uri = updateUsk(e.newURI);
			}
		}

		return uri;
	}

	private FreenetURI normalizeUri(FreenetURI uri) {
		try {

			if (uri.isUSK()) {
				uri = uri.sskForUSK();
			}
			if (uri.hasMetaStrings()) {
				uri = uri.setMetaString(null);
			}
			return uri;

		} catch (Exception e) {
			plugin.log("Reinserter.normalizeUri(): " + e.getMessage(), 0);
			return null;
		}
	}

	private void registerManifestUri(FreenetURI uri, int nLevel) {
		try {

			uri = normalizeUri(uri);
			if (manifestURIs.containsKey(uri)) {
				log("-> already registered manifest", nLevel, 2);
			} else {
				manifestURIs.put(uri, null);
				if (nLevel != -1) {
					log("-> registered manifest", nLevel, 2);
				}
			}

		} catch (Exception e) {
			plugin.log("Reinserter.registerManifestUri(): " + e.getMessage(), 0);
		}
	}

	private void registerBlockUri(FreenetURI uri, boolean newSegment, boolean isDataBlock, int logTabLevel) {
		try {

			if (uri != null) { // uri is null if metadata is created from splitfile

				// no reinsertion for SSK but go to sublevel
				if (!uri.isCHK()) {
					log("-> no reinsertion of USK, SSK or KSK", logTabLevel, 2);

					// check if uri already reinserted during this session
				} else if (blocks.containsKey(normalizeUri(uri))) {
					log("-> already registered block", logTabLevel, 2);

					// register
				} else {
					if (newSegment) {
						parsedSegmentId++;
						parsedBlockId = -1;
					}
					uri = normalizeUri(uri);
					blocks.put(uri, new Block(uri, parsedSegmentId, ++parsedBlockId, isDataBlock));
					log("-> registered block", logTabLevel, 2);
				}

			}

		} catch (Exception e) {
			plugin.log("Reinserter.registerBlockUri(): " + e.getMessage(), 0);
		}
	}

	public void registerBlockFetchSuccess(Block block) {
		try {

			segments.get(block.getSegmentId()).regFetchSuccess(block.isFetchSuccessful());

		} catch (Exception e) {
			plugin.log("Reinserter.registerBlockSuccess(): " + e.getMessage(), 0);
		}
	}

	public synchronized void updateSegmentStatistic(Segment segment, boolean success) {
		try {

			String successProp = plugin.getProp("success_segments_" + siteId);
			if (success) {
				successProp = successProp.substring(0, segment.getId()) + "1" + successProp.substring(segment.getId() + 1);
			}
			plugin.setProp("success_segments_" + siteId, successProp);
			plugin.saveProp();

		} catch (Exception e) {
			plugin.log("Reinserter.updateSegmentStatistic(): " + e.getMessage(), 0);
		}
	}

	public synchronized void updateBlockStatistic(int id, int success, int failed) {
		try {

			String[] successProp = plugin.getProp("success_" + siteId).split(",");
			successProp[id * 2] = String.valueOf(success);
			successProp[id * 2 + 1] = String.valueOf(failed);
			saveSuccessToProp(successProp);

		} catch (Exception e) {
			plugin.log("Reinserter.updateBlockStatistic(): " + e.getMessage(), 0);
		}
	}

	private void saveSuccessToProp(String[] success) {
		try {

			StringBuilder newSuccess = new StringBuilder();
			for (int i = 0; i < success.length; i++) {
				if (i > 0) newSuccess.append(",");
				newSuccess.append(success[i]);
			}
			plugin.setProp("success_" + siteId, newSuccess.toString());
			plugin.saveProp();

		} catch (Exception e) {
			plugin.log("Reinserter.updateBlockStatistic(): " + e.getMessage(), 0);
		}
	}

	public synchronized void terminate() {
		try {

			if (isActive() && isAlive()) {
				plugin.log("stop reinserter (" + siteId + ")", 1);
				log("*** stopped ***", 0);
				lastActivityTime = Integer.MIN_VALUE;
				plugin.setIntProp("active", -1);
				plugin.saveProp();
			}

		} catch (Exception e) {
			plugin.log("Reinserter.terminate(): " + e.getMessage(), 0);
		}
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
			long nDelay = (System.currentTimeMillis() - lastActivityTime) / 60 / 1000;
			return (nDelay < SingleJob.MAX_LIFETIME + 5);
		}
		return false;
	}

	public void log(int nSegmentId, String cMessage, int tabLevel, int nLogLevel) {
		StringBuilder buf = new StringBuilder();

		for (int i = 0; i < tabLevel; i++) {
			buf.append("    ");
		}
		if (nSegmentId != -1) {
			buf.insert(0, "(" + nSegmentId + ") ");
		}
		try {
			if (plugin.getIntProp("log_links") == 1) {
				int keyPos = cMessage.indexOf("K@");
				if (keyPos != -1) {
					keyPos = keyPos - 2;
					int keyPos2 = Math.max(cMessage.indexOf(" ", keyPos), cMessage.indexOf("<", keyPos));
					if (keyPos2 == -1) {
						keyPos2 = cMessage.length();
					}
					String cKey = cMessage.substring(keyPos, keyPos2);
					cMessage = cMessage.substring(0, keyPos) +
						 "<a href=\"/" + cKey + "\">" + cKey + "</a>" + cMessage.substring(keyPos2);
				}
			}
		} catch (Exception ignored) {
		}
		String cPrefix = buf.toString();
		plugin.log(plugin.getLogFilename(siteId), cPrefix + cMessage, nLogLevel);
	}

	public void log(Segment segment, String cMessage, int nLevel, int nLogLevel) {
		log(segment.getId(), cMessage, nLevel, nLogLevel);
	}

	public void log(Segment segment, String cMessage, int nLevel) {
		log(segment, cMessage, nLevel, 1);
	}

	public void log(String cMessage, int nLevel, int nLogLevel) {
		log(-1, cMessage, nLevel, nLogLevel);
	}

	public void log(String cMessage, int nLevel) {
		log(-1, cMessage, nLevel, 1);
	}

	public void log(Segment segment, String cMessage, Object obj) {
		if (obj != null) {
			log(segment, cMessage + " = ok", 1, 2);
		} else {
			log(segment, cMessage + " = null", 1, 2);
		}
	}

	private class ActivityGuard extends Thread {

		private final Reinserter reinserter;

		ActivityGuard(Reinserter reinserter) {
			this.reinserter = reinserter;
			lastActivityTime = System.currentTimeMillis();
		}

		@Override
		public synchronized void run() {
			try {
				this.setName("Keepalive - ActivityGuard");
				while (reinserter.isActive()) {
					wait(1000);
				}
				reinserter.terminate();
				long nStopCheckBegin = System.currentTimeMillis();
				while (reinserter.isAlive() && nStopCheckBegin > (System.currentTimeMillis() - (10 * 60 * 1000))) {
					try {
						wait(100);
					} catch (InterruptedException ignored) {
					}
				}
				if (!reinserter.isAlive()) {
					plugin.log("reinserter stopped (" + siteId + ")");
				} else {
					plugin.log("reinserter not stopped - stop was indicated 10 minutes before (" + siteId + ")");
				}

			} catch (InterruptedException e) {
				plugin.log("Reinserter.ActivityGuard.run(): " + e.getMessage(), 0);
			}
		}
	}

	public int getSiteId() {
		return siteId;
	}

	public ArrayList<Segment> getSegments() {
		return segments;
	}
}
