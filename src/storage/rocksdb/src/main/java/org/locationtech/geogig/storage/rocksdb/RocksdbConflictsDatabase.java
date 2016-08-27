/* Copyright (c) 2016 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Gabriel Roldan (Boundless) - initial implementation
 */
package org.locationtech.geogig.storage.rocksdb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.propagate;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jdt.annotation.Nullable;
import org.locationtech.geogig.model.ObjectId;
import org.locationtech.geogig.repository.Conflict;
import org.locationtech.geogig.storage.ConflictsDatabase;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

class RocksdbConflictsDatabase implements ConflictsDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(RocksdbConflictsDatabase.class);

    private final File baseDirectory;

    private static final String NULL_TX_ID = ".default";

    private ConcurrentMap<String/* TxID */, DBHandle> dbsByTransaction = new ConcurrentHashMap<>();

    RocksdbConflictsDatabase(File baseDirectory) {
        checkNotNull(baseDirectory);
        checkArgument(baseDirectory.exists(), "base directory does not exist: %s", baseDirectory);
        checkArgument(baseDirectory.isDirectory() && baseDirectory.canWrite());
        this.baseDirectory = baseDirectory;
    }

    private Optional<RocksDB> getDb(@Nullable String txId) {
        String id = txId == null ? NULL_TX_ID : txId;
        DBHandle dbHandle = dbsByTransaction.get(id);
        if (dbHandle == null) {
            return Optional.absent();
        }
        return Optional.of(dbHandle.db);
    }

    private RocksDB getOrCreateDb(@Nullable String txId) {
        final String id = txId == null ? NULL_TX_ID : txId;
        DBHandle dbHandle = dbsByTransaction.get(id);
        if (dbHandle == null) {
            String dbpath = dbPath(txId);
            DBOptions address = new DBOptions(dbpath, false);
            dbHandle = RocksConnectionManager.INSTANCE.acquire(address);
            this.dbsByTransaction.put(id, dbHandle);
        }
        return dbHandle.db;
    }

    private String dbPath(@Nullable String txId) {
        String dbname = txId == null ? NULL_TX_ID : "." + txId;
        return new File(this.baseDirectory, dbname).getAbsolutePath();
    }

    @Override
    public void removeConflicts(@Nullable String txId) {
        RocksDB db = getDb(txId).orNull();
        if (db != null) {
            String hanldeId = txId == null ? NULL_TX_ID : txId;
            DBHandle dbHandle = this.dbsByTransaction.remove(hanldeId);
            Preconditions.checkNotNull(dbHandle);
            final String dbPath = dbPath(txId);
            final boolean lastHandle = RocksConnectionManager.INSTANCE.release(dbHandle);
            if (lastHandle) {
                // ok, we can just remove the db, there are no more users
                File dbdir = new File(dbPath);
                try {
                    deleteRecustive(dbdir);
                } catch (Exception e) {
                    LOG.error("Error deleting conflicts databse at " + dbPath, e);
                }
            } else {
                // bad luck, lets empty it the hard way
                removeByPrefix(txId, null);
            }
        }
    }

    private void deleteRecustive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File f : children) {
                    deleteRecustive(f);
                }
            }
        }
        file.delete();
    }

    public void close() {
        try {
            for (DBHandle db : dbsByTransaction.values()) {
                RocksConnectionManager.INSTANCE.release(db);
            }
        } finally {
            dbsByTransaction.clear();
        }
    }

    private byte[] key(String path) {
        return path.getBytes(Charsets.UTF_8);
    }

    @Nullable
    private Conflict getInternal(RocksDB db, String path) {
        Conflict c = null;
        byte[] bs;
        try {
            bs = db.get(key(path));
            if (bs != null) {
                c = new ConflictSerializer().read(bs);
            }
        } catch (Exception e) {
            throw propagate(e);
        }
        return c;
    }

    @Override
    public boolean hasConflicts(@Nullable String txId) {
        Optional<RocksDB> db = getDb(txId);
        boolean hasConflicts = false;
        if (db.isPresent()) {
            try (RocksIterator it = db.get().newIterator()) {
                it.seekToFirst();
                hasConflicts = it.isValid();
            }
        }
        return hasConflicts;
    }

    @Override
    public Optional<Conflict> getConflict(@Nullable String txId, String path) {
        Optional<RocksDB> db = getDb(txId);
        Conflict c = null;
        if (db.isPresent()) {
            c = getInternal(db.get(), path);
        }
        return Optional.fromNullable(c);
    }

    @Deprecated
    @Override
    public List<Conflict> getConflicts(@Nullable String txId, @Nullable String pathFilter) {
        return Lists.newArrayList(getByPrefix(txId, pathFilter));
    }

    @Override
    public Iterator<Conflict> getByPrefix(@Nullable String txId, @Nullable String prefixFilter) {
        return new BatchIterator(this, txId, prefixFilter);
    }

    @Override
    public long getCountByPrefix(@Nullable String txId, @Nullable String treePath) {
        RocksDB db = getDb(txId).orNull();
        if (db == null) {
            return 0L;
        }
        long count = 0;
        try (RocksIterator it = db.newIterator()) {
            byte[] prefixKey = null;
            if (treePath == null) {
                it.seekToFirst();
            } else {
                byte[] treeKey = key(treePath);
                prefixKey = key(treePath + "/");
                it.seek(treeKey);
                if (it.isValid() && Arrays.equals(treeKey, it.key())) {
                    count++;
                }
                it.seek(prefixKey);
            }
            while (it.isValid() && isPrefix(prefixKey, it.key())) {
                count++;
                it.next();
            }
        }
        return count;
    }

    private boolean isPrefix(@Nullable byte[] prefix, byte[] key) {
        if (prefix == null) {
            return true;
        }
        if (prefix.length > key.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (prefix[i] != key[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void addConflict(@Nullable String txId, Conflict conflict) {
        addConflicts(txId, Collections.singleton(conflict));
    }

    @Override
    public void addConflicts(@Nullable String txId, Iterable<Conflict> conflicts) {
        RocksDB db = getOrCreateDb(txId);
        ConflictSerializer serializer = new ConflictSerializer();
        try (WriteBatch batch = new WriteBatch()) {
            for (Conflict c : conflicts) {
                byte[] key = key(c.getPath());
                byte[] value = serializer.write(c);
                batch.put(key, value);
            }
            try (WriteOptions writeOptions = new WriteOptions()) {
                db.write(writeOptions, batch);
            }
        } catch (Exception e) {
            propagate(e);
        }
    }

    @Override
    public void removeConflict(@Nullable String txId, String path) {
        RocksDB db = getDb(txId).orNull();
        if (db == null) {
            return;
        }
        try {
            db.remove(key(path));
        } catch (RocksDBException e) {
            propagate(e);
        }
    }

    @Override
    public void removeConflicts(@Nullable String txId, Iterable<String> paths) {
        RocksDB db = getDb(txId).orNull();
        if (db == null) {
            return;
        }
        try (WriteOptions writeOptions = new WriteOptions()) {
            writeOptions.setSync(false);
            for (String path : paths) {
                db.remove(writeOptions, key(path));
            }
            writeOptions.sync();
        } catch (RocksDBException e) {
            propagate(e);
        }
    }

    @Override
    public Set<String> findConflicts(@Nullable String txId, Set<String> paths) {
        RocksDB db = getDb(txId).orNull();
        if (db == null) {
            return ImmutableSet.of();
        }
        Set<String> found = new HashSet<>();
        byte[] noData = new byte[0];
        try {
            for (String path : paths) {
                int size = db.get(key(path), noData);
                if (size > 0) {
                    found.add(path);
                }
            }
        } catch (RocksDBException e) {
            propagate(e);
        }
        return found;
    }

    @Override
    public void removeByPrefix(@Nullable String txId, @Nullable String pathPrefix) {
        RocksDB db = getDb(txId).orNull();
        if (db == null) {
            return;
        }

        final @Nullable byte[] prefix = pathPrefix == null ? null : key(pathPrefix + "/");
        try (WriteBatch batch = new WriteBatch()) {
            if (pathPrefix != null) {
                batch.remove(key(pathPrefix));
            }
            try (RocksIterator it = db.newIterator()) {
                if (prefix == null) {
                    it.seekToFirst();
                } else {
                    it.seek(prefix);
                }
                while (it.isValid()) {
                    byte[] key = it.key();
                    if (isPrefix(prefix, key)) {
                        batch.remove(key);
                    } else {
                        break;
                    }
                    it.next();
                }
            }
            try (WriteOptions opts = new WriteOptions()) {
                db.write(opts, batch);
            }
        } catch (RocksDBException e) {
            propagate(e);
        }

    }

    private static class BatchIterator extends AbstractIterator<Conflict> {

        private static final int BATCH_SIZE = 1000;

        private String txId;

        private RocksdbConflictsDatabase rocksConflicts;

        private String prefixFilter;

        private byte[] lastMatchKey;

        private boolean reachedEnd;

        private Iterator<Conflict> currentBatch;

        private final ConflictSerializer serializer = new ConflictSerializer();

        public BatchIterator(RocksdbConflictsDatabase rocksConflicts, @Nullable final String txId,
                @Nullable final String prefixFilter) {
            this.rocksConflicts = rocksConflicts;
            this.txId = txId;
            this.prefixFilter = prefixFilter;
            this.currentBatch = Collections.emptyIterator();

            if (prefixFilter != null) {
                Optional<RocksDB> txdb = rocksConflicts.getDb(txId);
                if (txdb.isPresent()) {
                    // get an exact prefix match to account for a conflict on the
                    // tree itself rather than its children
                    byte[] key = rocksConflicts.key(prefixFilter);
                    byte[] treeConflict;
                    try {
                        treeConflict = txdb.get().get(key);
                        if (treeConflict != null) {
                            this.currentBatch = Iterators
                                    .singletonIterator(serializer.read(treeConflict));
                        }
                    } catch (Exception e) {
                        propagate(e);
                    }
                }
            }

        }

        @Override
        protected Conflict computeNext() {
            if (currentBatch.hasNext()) {
                return currentBatch.next();
            }
            this.currentBatch = nextBatch();
            if (this.currentBatch == null) {
                return endOfData();
            }
            return computeNext();
        }

        @Nullable
        private Iterator<Conflict> nextBatch() {
            if (reachedEnd) {
                return null;
            }
            Optional<RocksDB> txdb = rocksConflicts.getDb(txId);
            if (!txdb.isPresent()) {
                return null;
            }
            final RocksDB db = txdb.get();

            List<Conflict> conflicts = new ArrayList<>(BATCH_SIZE);

            try {
                byte[] keyPrefix = keyPrefix(this.prefixFilter);

                try (RocksIterator rocksit = db.newIterator()) {
                    if (lastMatchKey == null) {
                        rocksit.seek(keyPrefix);
                    } else {
                        rocksit.seek(lastMatchKey);
                        // position at the next past last
                        if (rocksit.isValid()) {
                            rocksit.next();
                        }
                    }

                    while (rocksit.isValid() && conflicts.size() < BATCH_SIZE) {
                        byte[] key = rocksit.key();
                        if (isPrefix(keyPrefix, key)) {
                            lastMatchKey = key;
                            byte[] encoded = rocksit.value();
                            conflicts.add(serializer.read(encoded));
                        } else {
                            reachedEnd = true;
                            break;
                        }
                        rocksit.next();
                    }
                }
            } catch (IOException e) {
                throw propagate(e);
            }

            return conflicts.isEmpty() ? null : conflicts.iterator();
        }

        private boolean isPrefix(byte[] keyPrefix, byte[] key) {
            if (key.length < keyPrefix.length) {
                return false;
            }
            for (int i = 0; i < keyPrefix.length; i++) {
                if (keyPrefix[i] != key[i]) {
                    return false;
                }
            }
            return true;
        }

        private byte[] keyPrefix(@Nullable String prefixFilter) {
            if (null == prefixFilter) {
                return new byte[0];
            }
            if (!prefixFilter.endsWith("/")) {
                prefixFilter = prefixFilter + "/";
            }

            return this.rocksConflicts.key(prefixFilter);
        }

    }

    static class ConflictSerializer {

        private static final byte HAS_ANCESTOR = 0b00000001;

        private static final byte HAS_OURS = 0b00000010;

        private static final byte HAS_THEIRS = 0b00000100;

        void write(DataOutput out, ObjectId value) throws IOException {
            out.write(value.getRawValue());
        }

        public byte[] write(Conflict c) throws IOException {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            write(out, c);
            return out.toByteArray();
        }

        ObjectId readId(DataInput in) throws IOException {
            byte[] raw = new byte[ObjectId.NUM_BYTES];
            in.readFully(raw);
            return ObjectId.createNoClone(raw);
        }

        public void write(DataOutput out, Conflict value) throws IOException {

            String path = value.getPath();
            ObjectId ancestor = value.getAncestor();
            ObjectId ours = value.getOurs();
            ObjectId theirs = value.getTheirs();

            byte flags = ancestor.isNull() ? 0x00 : HAS_ANCESTOR;
            flags |= ours.isNull() ? 0x00 : HAS_OURS;
            flags |= theirs.isNull() ? 0x00 : HAS_THEIRS;

            out.writeByte(flags);
            out.writeUTF(path);
            if (!ancestor.isNull()) {
                write(out, ancestor);
            }
            if (!ours.isNull()) {
                write(out, ours);
            }
            if (!theirs.isNull()) {
                write(out, theirs);
            }
        }

        public Conflict read(byte[] bs) throws IOException {
            return read(ByteStreams.newDataInput(bs));
        }

        public Conflict read(DataInput in) throws IOException {
            byte flags = in.readByte();
            boolean hasAncestor = (flags & HAS_ANCESTOR) == HAS_ANCESTOR;
            boolean hasOurs = (flags & HAS_OURS) == HAS_OURS;
            boolean hasTheirs = (flags & HAS_THEIRS) == HAS_THEIRS;
            String path = in.readUTF();
            ObjectId ancestor = hasAncestor ? readId(in) : ObjectId.NULL;
            ObjectId ours = hasOurs ? readId(in) : ObjectId.NULL;
            ObjectId theirs = hasTheirs ? readId(in) : ObjectId.NULL;
            return new Conflict(path, ancestor, ours, theirs);
        }
    }

}
