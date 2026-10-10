/*
 * Copyright (C) 2026 Miku UI
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

package com.miku.android.systemui.dagger;

import com.android.systemui.CoreStartable;
import com.miku.android.systemui.emptyshade.EmptyShadeMascotController;
import com.miku.android.systemui.lockscreen.LockscreenMediaArtController;
import com.miku.android.systemui.smartpixels.SmartPixelsReceiver;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/** Miku {@link com.android.systemui.CoreStartable} bindings. */
@Module
public abstract class MikuCoreStartableModule {
    @Binds
    @IntoMap
    @ClassKey(SmartPixelsReceiver.class)
    abstract CoreStartable bindSmartPixelsReceiver(SmartPixelsReceiver receiver);

    @Binds
    @IntoMap
    @ClassKey(LockscreenMediaArtController.class)
    abstract CoreStartable bindLockscreenMediaArtController(
            LockscreenMediaArtController controller);

    @Binds
    @IntoMap
    @ClassKey(EmptyShadeMascotController.class)
    abstract CoreStartable bindEmptyShadeMascotController(EmptyShadeMascotController controller);
}
