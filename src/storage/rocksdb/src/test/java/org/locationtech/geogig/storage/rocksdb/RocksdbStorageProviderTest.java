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

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.locationtech.geogig.storage.StorageProvider;

public class RocksdbStorageProviderTest {

    @Test
    public void testSPI() {
        Iterable<StorageProvider> providers = StorageProvider.findProviders();
        boolean found = false;
        for (StorageProvider p : providers) {
            if (p instanceof RocksdbStorageProvider) {
                found = true;
                break;
            }
        }
        assertTrue("RocksdbStorageProvider not found using SPI", found);
    }

}
