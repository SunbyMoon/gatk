package org.broadinstitute.hellbender.tools.spark.sv.playground;

import htsjdk.samtools.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariationSparkProgramGroup;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.utils.gcs.BucketUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.HashSet;

/**
 * Created by shuang on 3/3/17.
 */
@CommandLineProgramProperties(summary="Find reads that have the requested read names and outputs a SAM file with the original SAM records.",
        oneLineSummary="Dump reads that have the requested read names.",
        usageExample = "InternalExtractOriginalSAMRecordsByNameSpark \\" +
                "-I /path/to/my/dir/longReads.sam -O /path/to/my/dir/structuralVariants.vcf \\" +
                "-R /path/to/my/reference/reference.2bit --fastaReference /path/to/my/reference/reference.fasta",
        programGroup = StructuralVariationSparkProgramGroup.class)
public final class InternalExtractOriginalSAMRecordsByNameSpark extends GATKSparkTool {
    private static final long serialVersionUID = 1L;
    private final Logger localLogger = LogManager.getLogger(InternalExtractOriginalSAMRecordsByNameSpark.class);

    @Argument(doc = "file containing list of read names", fullName = "readNameFile")
    private String readNameFile;

    @Argument(doc = "file to write reads to", shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME)
    private String outputSAM;

    @Override
    public boolean requiresReads() {
        return true;
    }

    @Override
    protected void runTool( final JavaSparkContext ctx ) {

        final Broadcast<HashSet<String>> namesToLookForBroadcast = ctx.broadcast(parseReadNames());
        final Broadcast<SAMFileHeader>           headerBroadCast = ctx.broadcast(getHeaderForReads());

        final JavaRDD<SAMRecord> reads = getUnfilteredReads().repartition(80)
                                                   .filter(read -> namesToLookForBroadcast.getValue().contains(read.getName()))
                                                   .map(read -> read.convertToSAMRecord(headerBroadCast.getValue())).cache();
        localLogger.info("Found these many reads: " + reads.count());

        try ( final SAMFileWriter writer = createSAMFileWriter(outputSAM, headerBroadCast.getValue(), false) ) {
            reads.collect().forEach(writer::addAlignment);
        } catch ( final UncheckedIOException ie) {
            throw new GATKException("Can't write SAM file to the specified location: " + outputSAM, ie);
        }
    }

    private HashSet<String> parseReadNames() {

        try ( final BufferedReader rdr =
                      new BufferedReader(new InputStreamReader(BucketUtils.openFile(readNameFile))) ) {
            final HashSet<String> namesToLookFor = new HashSet<>();
            String line;
            while ( (line = rdr.readLine()) != null ) {
                namesToLookFor.add(line.replace("@", "")
                                       .replace("/1", "")
                                       .replace("/2", ""));
            }
            localLogger.info("Number of read names: " + namesToLookFor.size());
            return namesToLookFor;
        }
        catch ( final IOException ioe ) {
            throw new GATKException("Unable to read names file from " + readNameFile, ioe);
        }
    }

    // Copied from AlignedAssemblyOrExcuse.
    private static SAMFileWriter createSAMFileWriter(final String samFile, final SAMFileHeader header, final boolean preOrdered) {
        final SAMFileWriterFactory factory = new SAMFileWriterFactory();
        final int lastDotIndex = samFile.lastIndexOf('.');
        if (lastDotIndex >= 0) {
            final String extension = samFile.substring(lastDotIndex).toLowerCase();
            if (extension.equals(BamFileIoUtils.BAM_FILE_EXTENSION)) {
                return factory.makeBAMWriter(header, preOrdered, BucketUtils.createFile(samFile));
            } else if (extension.equals(".sam")) {
                return factory.makeSAMWriter(header, preOrdered, BucketUtils.createFile(samFile));
            } else {
                throw new GATKException("unsupported read alignment file name extension (." + extension + ") in requested name: " + samFile);
            }
        } else {
            throw new GATKException("cannot determine the alignment file format from its name: " + samFile);
        }
    }
}
