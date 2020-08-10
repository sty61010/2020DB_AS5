/*******************************************************************************
 * Copyright 2016, 2018 vanilladb.org contributors
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
 *******************************************************************************/
package org.vanilladb.core.storage.tx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vanilladb.core.server.VanillaDb;
import org.vanilladb.core.storage.buffer.BufferMgr;
import org.vanilladb.core.storage.buffer.Buffer;
import org.vanilladb.core.storage.tx.concurrency.ConcurrencyMgr;
import org.vanilladb.core.storage.tx.recovery.RecoveryMgr;
import org.vanilladb.core.storage.log.LogSeqNum;
import org.vanilladb.core.sql.Constant;
import org.vanilladb.core.storage.file.BlockId;
import org.vanilladb.core.storage.record.RecordId;

/**
 * Provides transaction management for clients, ensuring that all transactions
 * are recoverable, and in general satisfy the ACID properties with specified
 * isolation level.
 */
public class Transaction {
	private class WriteInfo {
		private int offset;
		private Constant val;
		private BlockId blk;
		private int currentSlot;
		private LogSeqNum lsn;
		public WriteInfo(LogSeqNum lsn, int offset, BlockId blk, int currentSlot, Constant val) {
			this.offset = offset;
			this.val = val;
			this.blk = blk;
			this.lsn = lsn;
			this.currentSlot = currentSlot;
		}
		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (obj == null || !(obj.getClass().equals(WriteInfo.class)))
				return false;
			WriteInfo info = (WriteInfo) obj;
			return this.blk.equals(info.blk) && this.offset==info.offset && this.currentSlot==info.currentSlot;
		}
	}
	private static Logger logger = Logger.getLogger(Transaction.class.getName());

	private RecoveryMgr recoveryMgr;
	private ConcurrencyMgr concurMgr;
	private BufferMgr bufferMgr;
	private List<TransactionLifecycleListener> lifecycleListeners;
	private long txNum;
	private boolean readOnly;
	private List<WriteInfo> infoList;
	
	public void addWriteInfo(LogSeqNum lsn, int offset, Constant val, BlockId blk, int currentSlot) {
		WriteInfo info = new WriteInfo(lsn, offset, blk, currentSlot, val);
//		for (int i=infoList.size()-1; i >= 0; i--) {
//			WriteInfo oldInfo = infoList.get(i);
//			if(oldInfo.blk.equals(blk) && oldInfo.currentSlot==currentSlot && oldInfo.offset==offset) {
//				infoList.set(i, info);
//				break;
//			}
//		}
		if (infoList.contains(info)) {
			int i = infoList.indexOf(info);
			infoList.set(i, info);
		}
		infoList.add(info);
	}
	
	public void WriteBack() {
		boolean Unpined = true;
		Buffer buff = null;
		for (int i=0; i < infoList.size(); i++) {
			WriteInfo info = infoList.get(i);
			if (Unpined) {
				buff = bufferMgr.pin(info.blk);
				Unpined = false;
			}
			RecordId ri = new RecordId(info.blk, info.currentSlot);
			this.concurMgr.writeRecord(ri);
			buff.setVal(info.offset, info.val, this.txNum, info.lsn);
			if (i+1 < infoList.size() && !infoList.get(i+1).blk.equals(info.blk)) {
				bufferMgr.unpin(buff);
				Unpined = true;
			}
		}
		if (buff != null) {
			bufferMgr.unpin(buff);
		}
		infoList.clear();
		
	}
	
	public Constant ReadModified(RecordId ri, int offset) {
		for (int i=infoList.size()-1; i >= 0; i--) {
			WriteInfo info = infoList.get(i);
			if(info.blk.equals(ri.block()) && info.currentSlot==ri.id() && info.offset==offset)
				return info.val;
		}
		return null;
	}

	/**
	 * Creates a new transaction and associates it with a recovery manager, a
	 * concurrency manager, and a buffer manager. This constructor depends on
	 * the file, log, and buffer managers from {@link VanillaDb}, which are
	 * created during system initialization. Thus this constructor cannot be
	 * called until {@link VanillaDb#init(String)} is called first.
	 * 
	 * @param txMgr
	 *            the transaction manager
	 * @param concurMgr
	 *            the associated concurrency manager
	 * @param recoveryMgr
	 *            the associated recovery manager
	 * @param bufferMgr
	 *            the associated buffer manager
	 * @param readOnly
	 *            is read-only mode
	 * @param txNum
	 *            the number of the transaction
	 */
	public Transaction(TransactionMgr txMgr, TransactionLifecycleListener concurMgr,
			TransactionLifecycleListener recoveryMgr, TransactionLifecycleListener bufferMgr, boolean readOnly,
			long txNum) {
		this.concurMgr = (ConcurrencyMgr) concurMgr;
		this.recoveryMgr = (RecoveryMgr) recoveryMgr;
		this.bufferMgr = (BufferMgr) bufferMgr;
		this.txNum = txNum;
		this.readOnly = readOnly;

		lifecycleListeners = new LinkedList<TransactionLifecycleListener>();
		infoList = new ArrayList<WriteInfo>();
		// XXX: A transaction manager must be added before a recovery manager to
		// prevent the following scenario:
		// <COMMIT 1>
		// <NQCKPT 1,2>
		//
		// Although, it may create another scenario like this:
		// <NQCKPT 2>
		// <COMMIT 1>
		// But the current algorithm can still recovery correctly during this
		// scenario.
		addLifecycleListener(txMgr);
		/*
		 * A recover manager must be added before a concurrency manager. For
		 * example, if the transaction need to roll back, it must hold all locks
		 * until the recovery procedure complete.
		 */
		addLifecycleListener(recoveryMgr);
		addLifecycleListener(concurMgr);
		addLifecycleListener(bufferMgr);
	}

	public void addLifecycleListener(TransactionLifecycleListener listener) {
		lifecycleListeners.add(listener);
	}

	/**
	 * Commits the current transaction. Flushes all modified blocks (and their
	 * log records), writes and flushes a commit record to the log, releases all
	 * locks, and unpins any pinned blocks.
	 */
	public void commit() {
		WriteBack();
		for (TransactionLifecycleListener l : lifecycleListeners)
			l.onTxCommit(this);

		if (logger.isLoggable(Level.FINE))
			logger.fine("transaction " + txNum + " committed");
	}

	/**
	 * Rolls back the current transaction. Undoes any modified values, flushes
	 * those blocks, writes and flushes a rollback record to the log, releases
	 * all locks, and unpins any pinned blocks.
	 */
	public void rollback() {
		for (TransactionLifecycleListener l : lifecycleListeners)
			l.onTxRollback(this);

		if (logger.isLoggable(Level.FINE))
			logger.fine("transaction " + txNum + " rolled back");
	}

	/**
	 * Finishes the current statement. Releases slocks obtained so far for
	 * repeatable read isolation level and does nothing in serializable
	 * isolation level. This method should be called after each SQL statement.
	 */
	public void endStatement() {
		for (TransactionLifecycleListener l : lifecycleListeners)
			l.onTxEndStatement(this);
	}

	public long getTransactionNumber() {
		return this.txNum;
	}

	public boolean isReadOnly() {
		return this.readOnly;
	}

	public RecoveryMgr recoveryMgr() {
		return recoveryMgr;
	}

	public ConcurrencyMgr concurrencyMgr() {
		return concurMgr;
	}

	public BufferMgr bufferMgr() {
		return bufferMgr;
	}
	
	
}
