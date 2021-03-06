/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.filewatch;

import org.gradle.api.Action;
import org.gradle.api.internal.file.FileSystemSubset;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.util.DisconnectableInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultFileSystemChangeWaiter implements FileSystemChangeWaiter {

    private static final long QUIET_PERIOD = 250L;
    private static final int EOF = -1;
    private static final int KEY_CODE_CTRL_D = 4;

    private final ExecutorFactory executorFactory;
    private final FileWatcherFactory fileWatcherFactory;

    public DefaultFileSystemChangeWaiter(ExecutorFactory executorFactory, FileWatcherFactory fileWatcherFactory) {
        this.executorFactory = executorFactory;
        this.fileWatcherFactory = fileWatcherFactory;
    }

    @Override
    public void wait(FileSystemSubset taskFileSystemInputs, final DefaultBuildCancellationToken cancellationToken, Runnable notifier) {
        final InputStream systemInProxy;

        if (System.in instanceof DisconnectableInputStream) {
            systemInProxy = System.in;
        } else {
            systemInProxy = new DisconnectableInputStream(System.in);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final StoppableExecutor executorService = executorFactory.create("continuous building - wait");

        FileWatcher watcher = fileWatcherFactory.watch(
            taskFileSystemInputs,
            new Action<Throwable>() {
                @Override
                public void execute(Throwable throwable) {
                    error.set(throwable);
                    latch.countDown();
                }
            },
            new FileWatcherListener() {
                private IdleTimeout timeout;

                @Override
                public void onChange(final FileWatcher watcher, FileWatcherEvent event) {
                    if (timeout == null) {
                        timeout = new IdleTimeout(QUIET_PERIOD, new Runnable() {
                            @Override
                            public void run() {
                                watcher.stop();
                                latch.countDown();
                            }
                        });
                        executorService.execute(new Runnable() {
                            @Override
                            public void run() {
                                timeout.await();
                            }
                        });
                    }
                    timeout.tick();
                }
            }
        );

        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        int c = systemInProxy.read();
                        if (c == KEY_CODE_CTRL_D || c == EOF) {
                            cancellationToken.doCancel();
                            break;
                        }
                    }
                } catch (IOException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        });

        executorService.submit(new Runnable() {
            @Override
            public void run() {
                while (!cancellationToken.isCancellationRequested()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                latch.countDown();
            }
        });

        try {
            notifier.run();
            latch.await();
            Throwable throwable = error.get();
            if (throwable != null) {
                throw UncheckedException.throwAsUncheckedException(throwable);
            }
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            CompositeStoppable.stoppable(watcher, new Stoppable() {
                @Override
                public void stop() {
                    executorService.shutdownNow();
                }
            }).stop();
        }
    }

}
