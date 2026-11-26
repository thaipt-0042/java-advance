package com.sunasterisk.socialanalytics.service;

import com.sunasterisk.socialanalytics.excel.ExcelExport;
import java.io.IOException;
import java.io.OutputStream;

/** A built report, ready to be written out; close it afterwards to delete its temporary files. */
public record ExportedReport(String fileName, ExcelExport file) implements AutoCloseable {

    public void write(OutputStream out) throws IOException {
        file.write(out);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
