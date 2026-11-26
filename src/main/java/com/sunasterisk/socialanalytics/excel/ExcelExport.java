package com.sunasterisk.socialanalytics.excel;

import java.io.IOException;
import java.io.OutputStream;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * A workbook being built by {@link ExcelWriter}. Close it once written, or abandoned: {@link #close()} deletes the
 * temporary files of the streaming workbook.
 */
public final class ExcelExport implements AutoCloseable {

    private final SXSSFWorkbook workbook;

    ExcelExport(SXSSFWorkbook workbook) {
        this.workbook = workbook;
    }

    SXSSFWorkbook workbook() {
        return workbook;
    }

    public void write(OutputStream out) throws IOException {
        workbook.write(out);
    }

    @Override
    public void close() throws IOException {
        // SXSSFWorkbook.close() (POI 6) disposes too, but after closing the sheet files: on a workbook that was
        // never written, its dispose then fails to flush the rows still in memory and logs "Failed to dispose
        // sheet". Disposing first flushes and deletes the files while they are open.
        try {
            workbook.dispose();
        } finally {
            workbook.close();
        }
    }
}
