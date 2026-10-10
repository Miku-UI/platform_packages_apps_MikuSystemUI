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

import com.android.systemui.bundle.phone.PodModulePhone;
import com.android.systemui.controls.dagger.StartControlsStartableModule;
import com.android.systemui.dagger.DefaultComponentBinder;
import com.android.systemui.dagger.DependencyProvider;
import com.android.systemui.dagger.ReferenceSystemUIModule;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.SystemUICoreStartableModule;
import com.android.systemui.dagger.SystemUIModule;
import com.android.systemui.keyguard.CustomizationProvider;
import com.android.systemui.notifications.intelligence.rules.ui.NotificationRulesDefaultModule;
import com.android.systemui.settings.MultiUserUtilsModule;
import com.android.systemui.statusbar.NotificationInsetsModule;
import com.android.systemui.statusbar.QsFrameTranslateModule;
import com.android.systemui.unfold.SysUIUnfoldModule;
import com.android.systemui.util.StartBinderLoggerModule;
import com.android.systemui.wallpapers.dagger.WallpaperModule;

import dagger.Subcomponent;

/**
 * Phone SysUI graph for MikuSystemUI. Module list matches {@link
 * com.android.systemui.dagger.ReferenceSysUIComponent}; add Miku bindings via {@link
 * MikuCoreStartableModule}.
 */
@SysUISingleton
@Subcomponent(modules = {
        DefaultComponentBinder.class,
        DependencyProvider.class,
        MultiUserUtilsModule.class,
        NotificationInsetsModule.class,
        NotificationRulesDefaultModule.class,
        QsFrameTranslateModule.class,
        ReferenceSystemUIModule.class,
        StartControlsStartableModule.class,
        StartBinderLoggerModule.class,
        SystemUIModule.class,
        PodModulePhone.class,
        SystemUICoreStartableModule.class,
        SysUIUnfoldModule.class,
        WallpaperModule.class,
        MikuCoreStartableModule.class})
public interface MikuSysUIComponent extends SysUIComponent {

    @SysUISingleton
    @Subcomponent.Builder
    interface Builder extends SysUIComponent.Builder {
        MikuSysUIComponent build();
    }

    void inject(CustomizationProvider customizationProvider);
}
