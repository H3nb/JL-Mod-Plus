// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.installer;

import com.android.dx.command.dexer.ConversionResult;

import io.github.h3nb.jlmodplus.util.ConverterException;

/** Preserves structured converter evidence across installer/reconversion failure boundaries. */
final class ConversionFailureException extends ConverterException {
    private final ConversionResult result;

    ConversionFailureException(String message, ConversionResult result) {
        super(message);
        this.result = result;
    }

    ConversionResult getResult() {
        return result;
    }
}
