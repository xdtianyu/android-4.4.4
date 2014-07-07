/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.exchange.service;

import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.Intent;
import android.os.IBinder;

import com.android.emailcommon.provider.EmailContent;

/**
 * Base class for services that handle sync requests from the system SyncManager.
 * This class covers the boilerplate for using an {@link AbstractThreadedSyncAdapter}. Subclasses
 * should just implement their sync adapter, and override {@link #getSyncAdapter}.
 */
public abstract class AbstractSyncAdapterService extends Service {
    public AbstractSyncAdapterService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Make sure EmailContent is initialized in Exchange app
        EmailContent.init(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return getSyncAdapter().getSyncAdapterBinder();
    }

    /**
     * Subclasses should override this to supply an instance of its sync adapter. Best practice is
     * to create a singleton and return that.
     * @return An instance of the sync adapter.
     */
    protected abstract AbstractThreadedSyncAdapter getSyncAdapter();
}
