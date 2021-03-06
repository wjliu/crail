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

package com.ibm.crail.namenode.rpc.darpc;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.ibm.crail.namenode.rpc.NameNodeProtocol;
import com.ibm.crail.namenode.rpc.RpcNameNodeService;
import com.ibm.crail.utils.CrailUtils;
import com.ibm.darpc.RpcClientEndpoint;
import com.ibm.darpc.RpcServerEvent;

public class DaRPCServiceDispatcher extends DaRPCNameNodeProtocol {
	private static final Logger LOG = CrailUtils.getLogger();
	
	private RpcNameNodeService service;
	
	private AtomicLong totalOps;
	private AtomicLong createOps;
	private AtomicLong lookupOps;
	private AtomicLong setOps;
	private AtomicLong removeOps;
	private AtomicLong renameOps;
	private AtomicLong getOps;
	private AtomicLong getErr;
	private AtomicLong getWriteOps;
	private AtomicLong getReadOps;	
	
	public DaRPCServiceDispatcher(RpcNameNodeService service){
		this.service = service;
		
		this.totalOps = new AtomicLong(0);
		this.createOps = new AtomicLong(0);
		this.lookupOps = new AtomicLong(0);
		this.setOps = new AtomicLong(0);
		this.removeOps = new AtomicLong(0);
		this.renameOps = new AtomicLong(0);
		this.getOps = new AtomicLong(0);
		this.getErr = new AtomicLong(0);
		this.getWriteOps = new AtomicLong(0);
		this.getReadOps = new AtomicLong(0);		
	}
	
	public void processServerEvent(RpcServerEvent<DaRPCNameNodeRequest, DaRPCNameNodeResponse> event) {
		DaRPCNameNodeRequest request = event.getRequest();
		DaRPCNameNodeResponse response = event.getResponse();
		short error = NameNodeProtocol.ERR_OK;
		try {
			response.setType(NameNodeProtocol.responseTypes[request.getCmd()]);
			response.setError((short) 0);
			switch(request.getCmd()) {
			case NameNodeProtocol.CMD_CREATE_FILE:
				this.createOps.incrementAndGet();
				error = service.createFile(request.createFile(), response.createFile(), response);
				break;			
			case NameNodeProtocol.CMD_GET_FILE:
				this.lookupOps.incrementAndGet();
				error = service.getFile(request.getFile(), response.getFile(), response);
				break;
			case NameNodeProtocol.CMD_SET_FILE:
				this.setOps.incrementAndGet();
				error = service.setFile(request.setFile(), response.getVoid(), response);
				break;
			case NameNodeProtocol.CMD_REMOVE_FILE:
				this.removeOps.incrementAndGet();
				error = service.removeFile(request.removeFile(), response.delFile(), response);
				break;				
			case NameNodeProtocol.CMD_RENAME_FILE:
				this.renameOps.incrementAndGet();
				error = service.renameFile(request.renameFile(), response.getRename(), response);
				break;		
			case NameNodeProtocol.CMD_GET_BLOCK:
				this.getOps.incrementAndGet();
				error = service.getBlock(request.getBlock(), response.getBlock(), response);
				if (error != NameNodeProtocol.ERR_OK){
					this.getErr.incrementAndGet();
				}
				break;
			case NameNodeProtocol.CMD_GET_LOCATION:
				error = service.getLocation(request.getLocation(), response.getLocation(), response);
				break;				
			case NameNodeProtocol.CMD_SET_BLOCK:
				error = service.setBlock(request.setBlock(), response.getVoid(), response);
				break;
			case NameNodeProtocol.CMD_GET_DATANODE:
				error = service.getDataNode(request.getDataNode(), response.getDataNode(), response);
				break;					
			case NameNodeProtocol.CMD_DUMP_NAMENODE:
				error = service.dump(request.dumpNameNode(), response.getVoid(), response);
				break;			
			case NameNodeProtocol.CMD_PING_NAMENODE:
				error = service.ping(request.pingNameNode(), response.pingNameNode(), response);
				break;
			default:
				error = NameNodeProtocol.ERR_INVALID_RPC_CMD;
				LOG.info("Rpc command not valid, opcode " + request.getCmd());
			}
		} catch(Exception e){
			error = NameNodeProtocol.ERR_UNKNOWN;
			LOG.info(NameNodeProtocol.messages[NameNodeProtocol.ERR_UNKNOWN] + e.getMessage());
			e.printStackTrace();
		}
		
		try {
			response.setError(error);
			this.totalOps.incrementAndGet();
			event.triggerResponse();
		} catch(Exception e){
			LOG.info("ERROR: RPC failed, messagesSend ");
			e.printStackTrace();
		}
	}

	@Override
	public void close(
			RpcClientEndpoint<DaRPCNameNodeRequest, DaRPCNameNodeResponse> endpoint) {
		try {
			LOG.info("disconnecting RPC connection, qpnum " + endpoint.getQp().getQp_num());
			endpoint.close();
		} catch(Exception e){
		}
	}	
}
