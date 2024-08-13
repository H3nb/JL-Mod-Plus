/*
 * Copyright 2024 Yury Kharchenko
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
 */

package ru.playsoftware.j2meloader.crashes.dialog

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.acra.ReportField
import org.acra.file.CrashReportPersister
import java.io.File

class CrashViewModel : ViewModel() {
    private val _stackTrace: MutableLiveData<String?> = MutableLiveData()
    var stackTrace: String? = null
        private set

    fun loadStackTrace(reportFile: File): LiveData<String?> {
        viewModelScope.launch(Dispatchers.IO) {
            reportFile.runCatching {
                CrashReportPersister().load(this).getString(ReportField.STACK_TRACE)
            }.onFailure(Throwable::printStackTrace).getOrNull()?.also {
                stackTrace = it
            }?.lines()?.run {
                filter { it.startsWith("Caused by:") }.joinToString("\n", this[0] + "\n")
            }.let(_stackTrace::postValue)
        }

        return _stackTrace
    }
}
