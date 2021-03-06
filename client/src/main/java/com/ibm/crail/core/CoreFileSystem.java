/*
 * Crail: A Multi-tiered Distributed Direct Access File System
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.crail.core;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.ibm.crail.CrailBlockLocation;
import com.ibm.crail.CrailDirectory;
import com.ibm.crail.CrailFile;
import com.ibm.crail.CrailFS;
import com.ibm.crail.CrailNode;
import com.ibm.crail.CrailOutputStream;
import com.ibm.crail.CrailResult;
import com.ibm.crail.conf.CrailConfiguration;
import com.ibm.crail.conf.CrailConstants;
import com.ibm.crail.datanode.DataNode;
import com.ibm.crail.namenode.protocol.BlockInfo;
import com.ibm.crail.namenode.protocol.DataNodeInfo;
import com.ibm.crail.namenode.protocol.FileInfo;
import com.ibm.crail.namenode.protocol.FileName;
import com.ibm.crail.namenode.rpc.NameNodeProtocol;
import com.ibm.crail.namenode.rpc.RpcNameNode;
import com.ibm.crail.namenode.rpc.RpcNameNodeClient;
import com.ibm.crail.namenode.rpc.RpcNameNodeFuture;
import com.ibm.crail.namenode.rpc.RpcResponseMessage;
import com.ibm.crail.utils.BlockCache;
import com.ibm.crail.utils.BufferCheckpoint;
import com.ibm.crail.utils.DirectBufferCache;
import com.ibm.crail.utils.EndpointCache;
import com.ibm.crail.utils.MappedBufferCache;
import com.ibm.crail.utils.NextBlockCache;
import com.ibm.crail.utils.CrailUtils;
import com.ibm.crail.utils.BlockCache.FileBlockCache;
import com.ibm.crail.utils.NextBlockCache.FileNextBlockCache;

public class CoreFileSystem extends CrailFS {
	private static final Logger LOG = CrailUtils.getLogger();
	private static AtomicInteger fsCount = new AtomicInteger(0);
	
	//namenode operations
	private RpcNameNode rpcNameNode;
	private RpcNameNodeClient namenodeClientRpc;
	
	//datanode operations
	private EndpointCache datanodeEndpointCache;
	
	private AtomicLong streamCounter;
	private ConcurrentHashMap<Long, CoreStream> openStreams;
	
	private BlockCache blockCache;
	private NextBlockCache nextBlockCache;
	private DirectBufferCache bufferCache;
	private BufferCheckpoint bufferCheckpoint;
	
	private boolean isOpen;
	private int fsId;
	private int hostHash;
	
	private CoreIOStatistics ioStatsIn;
	private CoreIOStatistics ioStatsOut;
	private CoreStreamStatistics streamStats;
	
	public CoreFileSystem(CrailConfiguration conf) throws Exception {
		CrailConstants.updateConstants(conf);
		CrailConstants.printConf();
		CrailConstants.verify();	
		
		//Datanodes
		StringTokenizer tokenizer = new StringTokenizer(CrailConstants.DATANODE_TYPES, ",");
		LinkedList<DataNode> dataNodeClients = new LinkedList<DataNode>(); 
		while (tokenizer.hasMoreTokens()){
			String name = tokenizer.nextToken();
			DataNode dataNode = DataNode.createInstance(name);
			dataNode.init(conf, null);
			dataNode.printConf(LOG);
			dataNodeClients.add(dataNode);
		}
		this.datanodeEndpointCache = new EndpointCache(fsId, dataNodeClients);
		
		//Namenode
		InetSocketAddress nnAddr = CrailUtils.getNameNodeAddress();
		this.rpcNameNode = RpcNameNode.createInstance(CrailConstants.NAMENODE_RPC_TYPE);
		this.namenodeClientRpc = rpcNameNode.getRpcClient(nnAddr);
		LOG.info("connected to namenode at " + nnAddr);		
		
		//Client
		this.fsId = fsCount.getAndIncrement();
		this.hostHash = InetAddress.getLocalHost().getHostName().hashCode();
		this.bufferCache = new MappedBufferCache();
		this.blockCache = new BlockCache();
		this.nextBlockCache = new NextBlockCache();
		this.openStreams = new ConcurrentHashMap<Long, CoreStream>();
		this.streamCounter = new AtomicLong(0);
		this.isOpen = true;
		this.bufferCheckpoint = new BufferCheckpoint();
		this.ioStatsIn = new CoreIOStatistics();
		this.ioStatsOut = new CoreIOStatistics();
		this.streamStats = new CoreStreamStatistics();
	}
	
	public Future<CrailFile> createFile(String path, int storageAffinity, int locationAffinity) throws Exception {
		FileName name = new FileName(path);
		
		if (CrailConstants.DEBUG){
			LOG.info("createFile: name " + path + ", storageAffinity " + storageAffinity + ", locationAffinity " + locationAffinity);
		}

		RpcNameNodeFuture<RpcResponseMessage.CreateFileRes> fileRes = namenodeClientRpc.createFile(name, false, storageAffinity, locationAffinity);
		return new CreateFileFuture(this, path, fileRes, storageAffinity, locationAffinity);
	}	
	
	CoreFile _createFile(RpcResponseMessage.CreateFileRes fileRes, String path, int storageAffinity, int locationAffinity) throws Exception {
		if (fileRes.getError() == NameNodeProtocol.ERR_PARENT_MISSING){
			throw new IOException("create: " + NameNodeProtocol.messages[fileRes.getError()] + ", name " + path);
		} else if  (fileRes.getError() == NameNodeProtocol.ERR_FILE_EXISTS){
			throw new IOException("create: " + NameNodeProtocol.messages[fileRes.getError()] + ", name " + path);
		} else if (fileRes.getError() != NameNodeProtocol.ERR_OK){
			LOG.info("create: " + NameNodeProtocol.messages[fileRes.getError()] + ", name " + path);
			throw new IOException("createFile: " + NameNodeProtocol.messages[fileRes.getError()] + ", error " + fileRes.getError());
		}		
		
		FileInfo fileInfo = fileRes.getFile();
		FileInfo dirInfo = fileRes.getParent();
		if (fileInfo == null || dirInfo == null){
			throw new IOException("createFile: " + NameNodeProtocol.messages[NameNodeProtocol.ERR_UNKNOWN]);
		}

		blockCache.remove(fileInfo.getFd());
		nextBlockCache.remove(fileInfo.getFd());
		
		BlockInfo fileBlock = fileRes.getFileBlock();
		getBlockCache(fileInfo.getFd()).put(CoreSubOperation.createKey(fileInfo.getFd(), 0), fileBlock);
		BlockInfo dirBlock = fileRes.getDirBlock();
		getBlockCache(dirInfo.getFd()).put(CoreSubOperation.createKey(dirInfo.getFd(), fileInfo.getDirOffset()), dirBlock);
		
		CoreDirectory dirFile = new CoreDirectory(this, dirInfo, CrailUtils.getParent(path));
		DirectoryOutputStream stream = this.getDirectoryOutputStream(dirFile);
		DirectoryRecord record = new DirectoryRecord(true, path);
		Future<CrailResult> future = stream.writeRecord(record, fileInfo.getDirOffset());
		
		if (CrailConstants.DEBUG){
			LOG.info("createFile: name " + path + ", success, fd " + fileInfo.getFd() + ", token " + fileInfo.getToken());
		}
		
		return new CoreCreateFile(this, fileInfo, path, storageAffinity, locationAffinity, future, stream);		
	}
	
	public Future<CrailDirectory> makeDirectory(String path) throws Exception {
		FileName name = new FileName(path);
		
		if (CrailConstants.DEBUG){
			LOG.info("makeDirectory: name " + path);
		}

		RpcNameNodeFuture<RpcResponseMessage.CreateFileRes> fileRes = namenodeClientRpc.createFile(name, true, 0, 0);
		return new MakeDirFuture(this, path, fileRes);
	}	
	
	CoreDirectory _makeDirectory(RpcResponseMessage.CreateFileRes fileRes, String path) throws Exception {
		if (fileRes.getError() == NameNodeProtocol.ERR_PARENT_MISSING){
			throw new IOException("makeDirectory: " + NameNodeProtocol.messages[fileRes.getError()] + ", name " + path);
		} else if  (fileRes.getError() == NameNodeProtocol.ERR_FILE_EXISTS){
			throw new IOException("makeDirectory: " + NameNodeProtocol.messages[fileRes.getError()] + ", name " + path);
		} else if (fileRes.getError() != NameNodeProtocol.ERR_OK){
			LOG.info("makeDirectory: " + NameNodeProtocol.messages[fileRes.getError()] + ", name " + path);
			throw new IOException("makeDirectory: " + NameNodeProtocol.messages[fileRes.getError()] + ", error " + fileRes.getError());
		}		
		
		FileInfo fileInfo = fileRes.getFile();
		FileInfo dirInfo = fileRes.getParent();
		if (fileInfo == null || dirInfo == null){
			throw new IOException("makeDirectory: " + NameNodeProtocol.messages[NameNodeProtocol.ERR_UNKNOWN]);
		}

		blockCache.remove(fileInfo.getFd());
		nextBlockCache.remove(fileInfo.getFd());
		
		BlockInfo fileBlock = fileRes.getFileBlock();
		getBlockCache(fileInfo.getFd()).put(CoreSubOperation.createKey(fileInfo.getFd(), 0), fileBlock);
		BlockInfo dirBlock = fileRes.getDirBlock();
		getBlockCache(dirInfo.getFd()).put(CoreSubOperation.createKey(dirInfo.getFd(), fileInfo.getDirOffset()), dirBlock);
		
		CoreDirectory dirFile = new CoreDirectory(this, dirInfo, CrailUtils.getParent(path));
		DirectoryOutputStream stream = this.getDirectoryOutputStream(dirFile);
		DirectoryRecord record = new DirectoryRecord(true, path);
		Future<CrailResult> future = stream.writeRecord(record, fileInfo.getDirOffset());		
		
		if (CrailConstants.DEBUG){
			LOG.info("makeDirectory: name " + path + ", success, fd " + fileInfo.getFd() + ", token " + fileInfo.getToken());
		}
		
		return new CoreMakeDirectory(this, fileInfo, path, future, stream);		
	}
	
	
	public Future<CrailFile> lookupFile(String path, boolean writeable) throws Exception {
		FileName name = new FileName(path);
		
		if (CrailConstants.DEBUG){
			LOG.info("lookupFile: path " + path + ", writeable " + writeable);
		}
		
		RpcNameNodeFuture<RpcResponseMessage.GetFileRes> fileRes = namenodeClientRpc.getFile(name, writeable);
		return new LookupFileFuture(this, path, fileRes);
	}	
	
	CoreFile _lookupFile(RpcResponseMessage.GetFileRes fileRes, String path) throws Exception {
		if (fileRes.getError() == NameNodeProtocol.ERR_GET_FILE_FAILED){
			return null;
		}
		else if (fileRes.getError() != NameNodeProtocol.ERR_OK){
			LOG.info("lookupFile: " + NameNodeProtocol.messages[fileRes.getError()]);
			return null;
		}		
		
		FileInfo fileInfo = fileRes.getFile();
		
		if (fileInfo != null){
			if (CrailConstants.DEBUG){
				LOG.info("lookupFile: name " + path + ", success, fd " + fileInfo.getFd());
			}
			BlockInfo fileBlock = fileRes.getFileBlock();
			getBlockCache(fileInfo.getFd()).put(CoreSubOperation.createKey(fileInfo.getFd(), 0), fileBlock);
			return new CoreLookupFile(this, fileInfo, path);
		} else {
			return null;
		}
	}	
	
	public Future<CrailDirectory> lookupDirectory(String path) throws Exception {
		FileName name = new FileName(path);
		
		if (CrailConstants.DEBUG){
			LOG.info("lookupDirectory: path " + path);
		}
		
		RpcNameNodeFuture<RpcResponseMessage.GetFileRes> fileRes = namenodeClientRpc.getFile(name, false);
		return new LookupDirectoryFuture(this, path, fileRes);
	}	
	
	CrailDirectory _lookupDirectory(RpcResponseMessage.GetFileRes fileRes, String path) throws Exception {
		if (fileRes.getError() == NameNodeProtocol.ERR_GET_FILE_FAILED){
			return null;
		}
		else if (fileRes.getError() != NameNodeProtocol.ERR_OK){
			LOG.info("lookupDirectory: " + NameNodeProtocol.messages[fileRes.getError()]);
			return null;
		}		
		
		FileInfo fileInfo = fileRes.getFile();
		
		if (fileInfo != null){
			if (CrailConstants.DEBUG){
				LOG.info("lookup: name " + path + ", success, fd " + fileInfo.getFd());
			}
			BlockInfo fileBlock = fileRes.getFileBlock();
			getBlockCache(fileInfo.getFd()).put(CoreSubOperation.createKey(fileInfo.getFd(), 0), fileBlock);
			return new CoreDirectory(this, fileInfo, path);
		} else {
			return null;
		}
	}
	
	public Future<CrailNode> lookupNode(String path) throws Exception {
		FileName name = new FileName(path);
		
		if (CrailConstants.DEBUG){
			LOG.info("lookupDirectory: path " + path);
		}
		
		RpcNameNodeFuture<RpcResponseMessage.GetFileRes> fileRes = namenodeClientRpc.getFile(name, false);
		return new LookupNodeFuture(this, path, fileRes);
	}	
	
	CoreNode _lookupNode(RpcResponseMessage.GetFileRes fileRes, String path) throws Exception {
		if (fileRes.getError() == NameNodeProtocol.ERR_GET_FILE_FAILED){
			return null;
		}
		else if (fileRes.getError() != NameNodeProtocol.ERR_OK){
			LOG.info("lookupDirectory: " + NameNodeProtocol.messages[fileRes.getError()]);
			return null;
		}		
		
		FileInfo fileInfo = fileRes.getFile();
		
		if (fileInfo != null){
			if (CrailConstants.DEBUG){
				LOG.info("lookup: name " + path + ", success, fd " + fileInfo.getFd());
			}
			BlockInfo fileBlock = fileRes.getFileBlock();
			getBlockCache(fileInfo.getFd()).put(CoreSubOperation.createKey(fileInfo.getFd(), 0), fileBlock);
			return new CoreNode(this, fileInfo, path, 0, 0);
		} else {
			return null;
		}
	}	
	

	public Future<CrailNode> rename(String src, String dst) throws Exception {
		FileName srcPath = new FileName(src);
		FileName dstPath = new FileName(dst);
		
		if (CrailConstants.DEBUG){
			LOG.info("rename: srcname " + src + ", dstname " + dst);
		}
		
		RpcNameNodeFuture<RpcResponseMessage.RenameRes> renameRes = namenodeClientRpc.renameFile(srcPath, dstPath);
		return new RenameNodeFuture(this, src, dst, renameRes);
	}
	
	CrailNode _rename(RpcResponseMessage.RenameRes renameRes, String src, String dst) throws Exception {
		if (renameRes.getError() == NameNodeProtocol.ERR_SRC_FILE_NOT_FOUND){
			LOG.info("rename: " + NameNodeProtocol.messages[renameRes.getError()]);
			return null;
		}
		if (renameRes.getError() == NameNodeProtocol.ERR_DST_PARENT_NOT_FOUND){
			LOG.info("rename: " + NameNodeProtocol.messages[renameRes.getError()]);
			return null;
		}
		if (renameRes.getError() == NameNodeProtocol.ERR_FILE_EXISTS){
			LOG.info("rename: " + NameNodeProtocol.messages[renameRes.getError()]);
			return null;
		}
		if (renameRes.getError() != NameNodeProtocol.ERR_OK){
			LOG.info("rename: " + NameNodeProtocol.messages[renameRes.getError()]);
			throw new IOException(NameNodeProtocol.messages[renameRes.getError()]);			
		} 
		if (renameRes.getDstParent().getCapacity() < renameRes.getDstFile().getDirOffset() + CrailConstants.DIRECTORY_RECORD){
			LOG.info("rename: parent capacity does not match dst file offset, capacity " + renameRes.getDstParent().getCapacity() + ", offset " + renameRes.getDstFile().getDirOffset());
		}		
		
		FileInfo srcParent = renameRes.getSrcParent();
		FileInfo srcFile = renameRes.getSrcFile();
		FileInfo dstDir = renameRes.getDstParent();
		FileInfo dstFile = renameRes.getDstFile();
		
		BlockInfo srcBlock = renameRes.getSrcBlock();
		getBlockCache(srcParent.getFd()).put(CoreSubOperation.createKey(srcParent.getFd(), srcFile.getDirOffset()), srcBlock);
		BlockInfo dirBlock = renameRes.getDstBlock();
		getBlockCache(dstDir.getFd()).put(CoreSubOperation.createKey(dstDir.getFd(), dstFile.getDirOffset()), dirBlock);		
		
		CoreDirectory dirSrc = new CoreDirectory(this, srcParent, CrailUtils.getParent(src));
		DirectoryOutputStream streamSrc = this.getDirectoryOutputStream(dirSrc);
		DirectoryRecord recordSrc = new DirectoryRecord(false, src);
		Future<CrailResult> futureSrc = streamSrc.writeRecord(recordSrc, srcFile.getDirOffset());			
		
		CoreDirectory dirDst = new CoreDirectory(this, dstDir, CrailUtils.getParent(dst));
		DirectoryOutputStream streamDst = this.getDirectoryOutputStream(dirDst);
		DirectoryRecord recordDst = new DirectoryRecord(true, dst);
		Future<CrailResult> futureDst = streamDst.writeRecord(recordDst, dstFile.getDirOffset());			

		blockCache.remove(srcFile.getFd());
		
		if (CrailConstants.DEBUG){
			LOG.info("rename: srcname " + src + ", dstname " + dst + ", success");
		}
		
		return new CoreRenamedNode(this, dstFile, dst, futureSrc, futureDst, streamSrc, streamDst);		
	}
	
	public Future<CrailNode> delete(String path, boolean recursive) throws Exception {
		FileName name = new FileName(path);
		
		if (CrailConstants.DEBUG){
			LOG.info("delete: name " + path + ", recursive " + recursive);
		}

		RpcNameNodeFuture<RpcResponseMessage.DeleteFileRes> fileRes = namenodeClientRpc.removeFile(name, recursive);
		return new DeleteNodeFuture(this, path, recursive, fileRes);
	}	
	
	CrailNode _delete(RpcResponseMessage.DeleteFileRes fileRes, String path, boolean recursive) throws Exception {
		if (fileRes.getError() == NameNodeProtocol.ERR_HAS_CHILDREN) {
			LOG.info("delete: " + NameNodeProtocol.messages[fileRes.getError()]);
			throw new IOException(NameNodeProtocol.messages[fileRes.getError()]);
		}
		if (fileRes.getError() != NameNodeProtocol.ERR_OK) {
			LOG.info("delete: " + NameNodeProtocol.messages[fileRes.getError()]);
			return null;
		} 
		
		FileInfo fileInfo = fileRes.getFile();
		FileInfo dirInfo = fileRes.getParent();
		
		CoreDirectory dirFile = new CoreDirectory(this, dirInfo, CrailUtils.getParent(path));
		DirectoryOutputStream stream = this.getDirectoryOutputStream(dirFile);
		DirectoryRecord record = new DirectoryRecord(false, path);
		Future<CrailResult> future = stream.writeRecord(record, fileInfo.getDirOffset());			
		
		blockCache.remove(fileInfo.getFd());
		
		if (CrailConstants.DEBUG){
			LOG.info("delete: name " + path + ", recursive " + recursive + ", success");
		}		
		
		return new CoreDeleteNode(this, fileInfo, path, future, stream);
	}	
	
	public DirectoryInputStream listEntries(String name) throws Exception {
		return _listEntries(name, CrailConstants.DIRECTORY_RANDOMIZE);
	}
	
	public DirectoryInputStream _listEntries(String name, boolean randomize) throws Exception {
		FileName directory = new FileName(name);
		
		if (CrailConstants.DEBUG){
			LOG.info("getDirectoryList: " + name);
		}

		RpcResponseMessage.GetFileRes fileRes = namenodeClientRpc.getFile(directory, false).get(CrailConstants.RPC_TIMEOUT, TimeUnit.MILLISECONDS);
		if (fileRes.getError() != NameNodeProtocol.ERR_OK) {
			LOG.info("getDirectoryList: " + NameNodeProtocol.messages[fileRes.getError()]);
			throw new FileNotFoundException(NameNodeProtocol.messages[fileRes.getError()]);
		}
		
		FileInfo dirInfo = fileRes.getFile();
		if (!dirInfo.isDir()){
			LOG.info("getDirectoryList: " + NameNodeProtocol.messages[NameNodeProtocol.ERR_FILE_IS_NOT_DIR]);
			throw new FileNotFoundException(NameNodeProtocol.messages[NameNodeProtocol.ERR_FILE_IS_NOT_DIR]);
		}
		
		CoreDirectory dirFile = new CoreDirectory(this, dirInfo, name);
		DirectoryInputStream inputStream = this.getDirectoryInputStream(dirFile, randomize);
		return inputStream;
	}	
	
	public CrailBlockLocation[] getBlockLocations(String path, long start, long len) throws Exception {
		if (CrailConstants.DEBUG){
			LOG.info("location: path " + path + ", start " + start + ", len " + len);
		}			
		
		if (path == null) {
			LOG.info("Path null");
			return null;
		}
		if (start < 0 || len < 0) {
			LOG.info("Start or len invalid");
			throw new IOException("Invalid start or len parameter");
		}
		
		
		FileName name = new FileName(path);
		long rangeStart = CrailUtils.blockStartAddress(start);
		long range = start + len - CrailUtils.blockStartAddress(start);
		long blockCount = range / CrailConstants.BLOCK_SIZE;
		if (range % CrailConstants.BLOCK_SIZE > 0){
			blockCount++;
		}
		CoreBlockLocation[] blockLocations = new CoreBlockLocation[(int) blockCount];
		HashMap<String, DataNodeInfo> dataNodeSet = new HashMap<String, DataNodeInfo>();
		HashMap<Long, String> offset2DataNode = new HashMap<Long, String>();
	
		for (long current = CrailUtils.blockStartAddress(start); current < start + len; current += CrailConstants.BLOCK_SIZE){
			RpcResponseMessage.GetLocationRes getLocationRes = namenodeClientRpc.getLocation(name, current).get(CrailConstants.RPC_TIMEOUT, TimeUnit.MILLISECONDS);
			if (getLocationRes.getError() != NameNodeProtocol.ERR_OK) {
				LOG.info("location: " + NameNodeProtocol.messages[getLocationRes.getError()]);
				throw new IOException(NameNodeProtocol.messages[getLocationRes.getError()]);
			}
			
			DataNodeInfo dataNodeInfo = getLocationRes.getBlockInfo().getDnInfo();
			dataNodeSet.put(dataNodeInfo.getInetAddress().toString(), dataNodeInfo);
			CoreBlockLocation location = new CoreBlockLocation();
			location.setOffset(current);
			location.setLength(Math.min(start + len - current, CrailConstants.BLOCK_SIZE));
			long index = (current - rangeStart) / CrailConstants.BLOCK_SIZE;
			blockLocations[(int) index] = location;
			offset2DataNode.put(current, dataNodeInfo.getInetAddress().toString());
		}
		
		//asign an identifier to each data node
		ArrayList<DataNodeInfo> dataNodeArray = new ArrayList<DataNodeInfo>(dataNodeSet.size());
		int index = 0;
		for (DataNodeInfo dn : dataNodeSet.values()){
			dataNodeArray.add(index, dn);
			index++;
		}
		
		int locationSize = Math.min(CrailConstants.SHADOW_REPLICATION, dataNodeSet.size());
		int blockIndex = 0;
		for (int i = 0; i < blockLocations.length; i++){
			CoreBlockLocation location = blockLocations[i];
			String[] hosts = new String[locationSize];
			String[] names = new String[locationSize];
			String[] topology = new String[locationSize];
			int[] storageTiers = new int[locationSize];
			int[] locationTiers = new int[locationSize];
			
			String key = offset2DataNode.get(location.getOffset());
			DataNodeInfo mainDataNode = dataNodeSet.get(key);
			InetSocketAddress address = mainDataNode.getInetAddress();
			names[0] = address.getAddress().getHostAddress() + ":" + address.getPort(); 
			hosts[0] = address.getAddress().getHostAddress();
			topology[0] = "/default-rack/" + names[0];
			storageTiers[0] = mainDataNode.getStorageTier();
			locationTiers[0] = mainDataNode.getLocationAffinity();
			for (int j = 1; j < locationSize; j++){
				DataNodeInfo replicaDataNode = dataNodeArray.get(blockIndex);
				address = replicaDataNode.getInetAddress();
				names[j] = address.getAddress().getHostAddress() + ":" + address.getPort(); 
				hosts[j] = address.getAddress().getHostAddress();
				topology[j] = "/default-rack/" + names[j];
				storageTiers[j] = replicaDataNode.getStorageTier();
				locationTiers[j] = replicaDataNode.getLocationAffinity();				
				blockIndex = (blockIndex + 1) % dataNodeArray.size();
			}
			location.setNames(names);
			location.setHosts(hosts);
			location.setTopologyPaths(topology);
			location.setStorageTiers(storageTiers);
			location.setLocationAffinities(locationTiers);
		}
		
		return blockLocations;
	}
	
	public void dumpNameNode() throws Exception {
		namenodeClientRpc.dumpNameNode().get(CrailConstants.RPC_TIMEOUT, TimeUnit.MILLISECONDS);
	}	
	
	public void ping() throws Exception {
		RpcResponseMessage.PingNameNodeRes pingRes = namenodeClientRpc.pingNameNode().get(CrailConstants.RPC_TIMEOUT, TimeUnit.MILLISECONDS);
		if (pingRes.getError() != NameNodeProtocol.ERR_OK) {
			LOG.info("Ping: " + NameNodeProtocol.messages[pingRes.getError()]);
			throw new IOException(NameNodeProtocol.messages[pingRes.getError()]);
		}		
	}

	public ByteBuffer allocateBuffer() throws IOException {
		return this.bufferCache.getBuffer();
	}
	
	public void freeBuffer(ByteBuffer buffer) throws IOException {
		this.bufferCache.putBuffer(buffer);
	}	
	
	public int getFsId() {
		return fsId;
	}	
	
	public int getHostHash() {
		return hostHash;
	}
	
	public BufferCheckpoint getBufferCheckpoint() {
		return bufferCheckpoint;
	}
	
	public void resetStatistics(){
		this.ioStatsIn.reset();
		this.ioStatsOut.reset();
		this.streamStats.reset();
		this.bufferCache.reset();
	}
	
	public void printStatistics(String message) {
		if (CrailConstants.STATISTICS){
			LOG.info("CoreFileSystem statistics, message " + message + 
					", total " + ioStatsIn.getTotalOps() + ", localOps " + ioStatsIn.getLocalOps() + ", remoteOps " + ioStatsIn.getRemoteOps() + ", localDirOps " + ioStatsIn.getLocalDirOps() + ", remoteDirOps " + ioStatsIn.getRemoteDirOps() + 
					", cached " + ioStatsIn.getCachedOps() + ", nonBlocking " + ioStatsIn.getNonblockingOps() + ", blocking " + ioStatsIn.getBlockingOps() +
					", prefetched " + ioStatsIn.getPrefetchedOps() + ", prefetchedNonBlocking " + ioStatsIn.getPrefetchedNonblockingOps() + ", prefetchedBlocking " + ioStatsIn.getPrefetchedBlockingOps() +
					", capacity " + ioStatsIn.getCapacity() + ", totalStreams " + ioStatsIn.getTotalStreams() + ", avgCapacity " + ioStatsIn.getAvgCapacity() +
					", avgOpLen " + ioStatsIn.getAvgOpLen() + 
					
					", total " + ioStatsOut.getTotalOps() + ", localOps " + ioStatsOut.getLocalOps() + ", remoteOps " + ioStatsOut.getRemoteOps() + ", localDirOps " + ioStatsOut.getLocalDirOps() + ", remoteDirOps " + ioStatsOut.getRemoteDirOps() + 
					", cached " + ioStatsOut.getCachedOps() + ", nonBlocking " + ioStatsOut.getNonblockingOps() + ", blocking " + ioStatsOut.getBlockingOps() +
					", prefetched " + ioStatsOut.getPrefetchedOps() + ", prefetchedNonBlocking " + ioStatsOut.getPrefetchedNonblockingOps() + ", prefetchedBlocking " + ioStatsOut.getPrefetchedBlockingOps() +
					", capacity " + ioStatsOut.getCapacity() + ", totalStreams " + ioStatsOut.getTotalStreams() + ", avgCapacity " + ioStatsOut.getAvgCapacity() +
					", avgOpLen " + ioStatsOut.getAvgOpLen() + 
					
					", cacheGet " + bufferCache.get() + ", cachePut " + bufferCache.put() + ", cacheMiss " + bufferCache.missed() + ", cacheMissMap " + bufferCache.missedMap() + ", cacheMissHeap " + bufferCache.missedHeap() + ", cacheSize " + bufferCache.size() +  ", cacheMax " + bufferCache.max() +
//					", mrOps " + mrCache.ops() + ", mrMisses " + mrCache.missed() +
					", endpointCache " + datanodeEndpointCache.size() + 
					", open " + streamStats.getOpen() + ", openInput " + streamStats.getOpenInput() + ", openOutput " + streamStats.getOpenOutput() + ", openInputDir " + streamStats.getOpenInputDir() + ", openOutputDir " + streamStats.getOpenOutputDir() + 
					", close " + streamStats.getClose() + ", closeInput " + streamStats.getCloseInput() + ", closeOutput " + streamStats.getCloseOutput() + ", closeInputDir " + streamStats.getCloseInputDir() + ", closeOutputDir " + streamStats.getCloseOutputDir() + 
					", maxInput " + streamStats.getMaxInput() + ", maxOutput " + streamStats.getMaxOutput());
		}
	}	
	
	public void closeFileSystem() throws Exception {
		if (!isOpen) {
			return;
		}
		
		LinkedList<CoreStream> streamTmp = new LinkedList<CoreStream>();
		for (CoreStream stream : openStreams.values()) {
			streamTmp.add(stream);
		}
		for (CoreStream stream : streamTmp) {
			stream.close();
		}
	
		bufferCache.close();
		datanodeEndpointCache.close();
		rpcNameNode.close();
		this.isOpen = false;
	}

	public void closeFile(FileInfo fileInfo) throws Exception {
		if (fileInfo.getToken() > 0){
			namenodeClientRpc.setFile(fileInfo, true).get(CrailConstants.RPC_TIMEOUT, TimeUnit.MILLISECONDS);				
		}
	}

	//-------------------------------------------------------------
	
	DirectoryOutputStream getDirectoryOutputStream(CoreDirectory directory) throws Exception {
		CoreOutputStream outputStream = getOutputStream(directory, 0);
		return new DirectoryOutputStream(outputStream);
	}
	
	DirectoryInputStream getDirectoryInputStream(CoreDirectory directory, boolean randomize) throws Exception {
		CoreInputStream inputStream = getInputStream(directory, 0);
		return new DirectoryInputStream(inputStream, randomize);
	}	

	CoreOutputStream getOutputStream(CoreNode file, long writeHint) throws Exception {
		CoreOutputStream outputStream = new CoreOutputStream(file, streamCounter.incrementAndGet(), writeHint);
		openStreams.put(outputStream.getStreamId(), outputStream);

		if (CrailConstants.STATISTICS){
			streamStats.incOpen();
			streamStats.incOpenOutput();
			streamStats.incCurrentOutput();
			streamStats.incMaxOutput();
			if (file.isDir()){
				streamStats.incOpenOutputDir();
			}
		}
		return outputStream;
	}		
	
	CoreInputStream getInputStream(CoreNode file, long readHint) throws Exception {
		CoreInputStream inputStream = new CoreInputStream(file, streamCounter.incrementAndGet(), readHint);
		openStreams.put(inputStream.getStreamId(), inputStream);
		
		if (CrailConstants.STATISTICS){
			streamStats.incOpen();
			streamStats.incOpenInput();
			streamStats.incCurrentInput();
			streamStats.incMaxInput();
			if (file.isDir()){
				streamStats.incOpenInputDir();
			}
		}
		return inputStream;
	}
	
	CoreStream unregister(CoreStream coreStream) {
		CoreStream stream = this.openStreams.remove(coreStream.getStreamId());
		if (stream != null && CrailConstants.STATISTICS){
			streamStats.incClose();
			if (stream instanceof CoreInputStream){
				streamStats.incCloseInput();
				this.ioStatsIn.add(stream.getCoreStatistics());
				streamStats.decCurrentInput();
				if (stream.getFile().isDir()){
					streamStats.incCloseInputDir();
				}
			} 
			if (stream instanceof CoreOutputStream){
				streamStats.incCloseOutput();
				this.ioStatsOut.add(stream.getCoreStatistics());
				streamStats.decCurrentOutput();
				if (stream.getFile().isDir()){
					streamStats.incCloseOutputDir();
				}
			}
		}
		
		return stream;
	}
	
	FileBlockCache getBlockCache(long fd){
		return blockCache.getFileBlockCache(fd);
	}
	
	FileNextBlockCache getNextBlockCache(long fd){
		return nextBlockCache.getFileBlockCache(fd);
	}	

	RpcNameNodeClient getNamenodeClientRpc() {
		return namenodeClientRpc;
	}

	EndpointCache getDatanodeEndpointCache() {
		return datanodeEndpointCache;
	}

	public DirectBufferCache getBufferCache() {
		return bufferCache;
	}

	public void purgeCache() {
		blockCache.purge();
		nextBlockCache.purge();
	}
}
