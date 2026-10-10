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

package com.miku.android.systemui.volume

import android.content.Context
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SysUISingleton
class VolumeDialogKeyPressViewModel
@Inject
constructor(context: Context, volumeKeyPressRepository: VolumeKeyPressRepository) {

    private val pressOffsetPx: Float =
        context.resources.getDimension(R.dimen.volume_dialog_key_press_offset)

    val pressOffsetPxFlow: Flow<Float> =
        volumeKeyPressRepository.volumeKeyPress
            .map { press ->
                when (press) {
                    VolumeKeyPress.Up -> -pressOffsetPx
                    VolumeKeyPress.Down -> pressOffsetPx
                    VolumeKeyPress.None -> 0f
                }
            }
            .distinctUntilChanged()
}
