/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.lock;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.oozie.service.MemoryLocksService;
import org.apache.oozie.service.ServiceException;
import org.apache.oozie.service.Services;
import org.apache.oozie.test.XTestCase;
import org.apache.oozie.util.XLog;

public class TestMemoryLocks extends XTestCase {
    private static final int LATCH_TIMEOUT = 10;
    private XLog log = XLog.getLog(getClass());

    private MemoryLocks locks;

    protected void setUp() throws Exception {
        super.setUp();
        locks = new MemoryLocks();
    }

    protected void tearDown() throws Exception {
        locks = null;
        super.tearDown();
    }

    public abstract class LatchHandler {
        protected CountDownLatch startLatch = new CountDownLatch(1);
        protected CountDownLatch acquireLockLatch = new CountDownLatch(1);
        protected CountDownLatch proceedingLatch = new CountDownLatch(1);
        protected CountDownLatch terminationLatch = new CountDownLatch(1);

        public void awaitStart() throws InterruptedException {
            startLatch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);
        }

        public void awaitTermination() throws InterruptedException {
            terminationLatch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);
        }

        public void awaitLockAcquire() throws InterruptedException {
            acquireLockLatch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);
        }

        public void proceed() {
            proceedingLatch.countDown();
        }
    }

    public abstract class Locker extends LatchHandler implements Runnable {
        protected String name;
        private String nameIndex;
        private StringBuffer sb;
        protected long timeout;

        public Locker(String name, int nameIndex, long timeout, StringBuffer buffer) {
            this.name = name;
            this.nameIndex = name + ":" + nameIndex;
            this.sb = buffer;
            this.timeout = timeout;
        }

        public void run() {
            try {
                log.info("Getting lock [{0}]", nameIndex);
                startLatch.countDown();
                MemoryLocks.MemoryLockToken token = getLock();
                if (token != null) {
                    log.info("Got lock [{0}]", nameIndex);
                    sb.append(nameIndex + "-L ");

                    acquireLockLatch.countDown();
                    proceedingLatch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);

                    sb.append(nameIndex + "-U ");
                    token.release();
                    log.info("Release lock [{0}]", nameIndex);
                }
                else {
                    proceedingLatch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);
                    sb.append(nameIndex + "-N ");
                    log.info("Did not get lock [{0}]", nameIndex);
                }
                terminationLatch.countDown();
            }
            catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        protected abstract MemoryLocks.MemoryLockToken getLock() throws InterruptedException;
    }

    public class ReadLocker extends Locker {

        public ReadLocker(String name, int nameIndex, long timeout, StringBuffer buffer) {
            super(name, nameIndex, timeout, buffer);
        }

        protected MemoryLocks.MemoryLockToken getLock() throws InterruptedException {
            return locks.getReadLock(name, timeout);
        }
    }

    public class WriteLocker extends Locker {

        public WriteLocker(String name, int nameIndex, long timeout, StringBuffer buffer) {
            super(name, nameIndex, timeout, buffer);
        }

        protected MemoryLocks.MemoryLockToken getLock() throws InterruptedException {
            return locks.getWriteLock(name, timeout);
        }
    }

    public void testWaitWriteLock() throws Exception {
        StringBuffer sb = new StringBuffer("");
        Locker l1 = new WriteLocker("a", 1, -1, sb);
        Locker l2 = new WriteLocker("a", 2, -1, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l2.awaitStart();

        l1.proceed();
        l2.proceed();

        l1.awaitTermination();
        l2.awaitTermination();

        assertEquals("a:1-L a:1-U a:2-L a:2-U", sb.toString().trim());
    }

    public void testNoWaitWriteLock() throws Exception {
        StringBuffer sb = new StringBuffer("");
        Locker l1 = new WriteLocker("a", 1, 0, sb);
        Locker l2 = new WriteLocker("a", 2, 0, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l2.awaitStart();

        l2.proceed();
        l2.awaitTermination();

        l1.proceed();
        l1.awaitTermination();

        assertEquals("a:1-L a:2-N a:1-U", sb.toString().trim());
    }

    public void testTimeoutWaitingWriteLock() throws Exception {
        StringBuffer sb = new StringBuffer("");
        Locker l1 = new WriteLocker("a", 1, 0, sb);
        Locker l2 = new WriteLocker("a", 2, 10000, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l2.awaitStart();

        l1.proceed();
        l1.awaitTermination();

        l2.proceed();
        l2.awaitTermination();

        assertEquals("a:1-L a:1-U a:2-L a:2-U", sb.toString().trim());
    }

    public void testTimeoutTimingOutWriteLock() throws Exception {
        StringBuffer sb = new StringBuffer("");
        Locker l1 = new WriteLocker("a", 1, 0, sb);
        Locker l2 = new WriteLocker("a", 2, 50, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l2.awaitStart();

        l2.proceed();
        l2.awaitTermination();  // L2 will time out after 50ms

        l1.proceed();
        l1.awaitTermination();

        assertEquals("a:1-L a:2-N a:1-U", sb.toString().trim());
    }

    public void testReadLock() throws Exception {
        StringBuffer sb = new StringBuffer("");
        Locker l1 = new ReadLocker("a", 1, -1, sb);
        Locker l2 = new ReadLocker("a", 2, -1, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();  // L1 is holding a readlock

        new Thread(l2).start();
        l2.awaitLockAcquire();  // both L1 & L2 are holding a readlock

        l1.proceed();
        l1.awaitTermination();

        l2.proceed();
        l2.awaitTermination();

        assertEquals("a:1-L a:2-L a:1-U a:2-U", sb.toString().trim());
    }

    public void testReadWriteLock() throws Exception {
        StringBuffer sb = new StringBuffer("");
        Locker l1 = new ReadLocker("a", 1, -1, sb);
        Locker l2 = new WriteLocker("a", 2, -1, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l2.awaitStart();

        l1.proceed();
        l1.awaitTermination();

        l2.proceed();
        l2.awaitTermination();

        assertEquals("a:1-L a:1-U a:2-L a:2-U", sb.toString().trim());
    }

    public void testWriteReadLock() throws Exception {
        StringBuffer sb = new StringBuffer("");
        Locker l1 = new WriteLocker("a", 1, -1, sb);
        Locker l2 = new ReadLocker("a", 2, -1, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l2.awaitStart();

        l1.proceed();
        l1.awaitTermination();

        l2.proceed();
        l2.awaitTermination();

        assertEquals("a:1-L a:1-U a:2-L a:2-U", sb.toString().trim());
    }

    public class SameThreadWriteLocker extends LatchHandler implements Runnable {
        protected String name;
        private String nameIndex;
        private StringBuffer sb;
        protected long timeout;

        public SameThreadWriteLocker(String name, int nameIndex, long timeout, StringBuffer buffer) {
            this.name = name;
            this.nameIndex = name + ":" + nameIndex;
            this.sb = buffer;
            this.timeout = timeout;
        }

        public void run() {
            try {
                startLatch.countDown();
                log.info("Getting lock [{0}]", nameIndex);
                MemoryLocks.MemoryLockToken token = getLock();
                MemoryLocks.MemoryLockToken token2 = getLock();

                if (token != null) {
                    acquireLockLatch.countDown();

                    log.info("Got lock [{0}]", nameIndex);
                    sb.append(nameIndex + "-L1 ");
                    if (token2 != null) {
                        sb.append(nameIndex + "-L2 ");
                    }
                    sb.append(nameIndex + "-U1 ");

                    proceedingLatch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);

                    token.release();
                    sb.append(nameIndex + "-U2 ");
                    token2.release();
                    log.info("Release lock [{0}]", nameIndex);
                }
                else {
                    proceedingLatch.await(LATCH_TIMEOUT, TimeUnit.SECONDS);
                    sb.append(nameIndex + "-N ");
                    log.info("Did not get lock [{0}]", nameIndex);
                }
                terminationLatch.countDown();
            }
            catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        protected MemoryLocks.MemoryLockToken getLock() throws InterruptedException {
            return locks.getWriteLock(name, timeout);
        }
    }

    public void testWriteLockSameThreadNoWait() throws Exception {
        StringBuffer sb = new StringBuffer("");
        SameThreadWriteLocker l1 = new SameThreadWriteLocker("a", 1, 0, sb);
        Locker l2 = new WriteLocker("a", 2, 0, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l1.awaitStart();

        l2.proceed();
        l2.awaitTermination();

        l1.proceed();
        l1.awaitTermination();

        assertEquals("a:1-L1 a:1-L2 a:1-U1 a:2-N a:1-U2", sb.toString().trim());
    }

    public void testWriteLockSameThreadWait() throws Exception {
        StringBuffer sb = new StringBuffer("");
        SameThreadWriteLocker l1 = new SameThreadWriteLocker("a", 1, 0, sb);
        Locker l2 = new WriteLocker("a", 2, 10000, sb);

        new Thread(l1).start();
        l1.awaitLockAcquire();

        new Thread(l2).start();
        l1.awaitStart();

        l1.proceed();
        l1.awaitTermination();

        l2.proceed();
        l2.awaitTermination();

        assertEquals("a:1-L1 a:1-L2 a:1-U1 a:1-U2 a:2-L a:2-U", sb.toString().trim());
    }

    public void testLockReentrant() throws ServiceException, InterruptedException {
        final String path = UUID.randomUUID().toString();
        MemoryLocksService lockService = new MemoryLocksService();
        try {
            lockService.init(Services.get());
            LockToken lock = lockService.getWriteLock(path, 5000);
            lock = (LockToken) lockService.getWriteLock(path, 5000);
            lock = (LockToken) lockService.getWriteLock(path, 5000);
            assertEquals(lockService.getMemoryLocks().size(), 1);
            lock.release();
            assertEquals(lockService.getMemoryLocks().size(), 1);
            lock.release();
            assertEquals(lockService.getMemoryLocks().size(), 1);
            lock.release();
            assertEquals(lockService.getMemoryLocks().size(), 0);
        }
        catch (Exception e) {
            fail("Reentrant property, it should have acquired lock");
        }
        finally {
            lockService.destroy();
        }
    }

}
