package org.juxtasoftware.resource;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.FileWriterWithEncoding;
import org.juxtasoftware.Constants;
import org.juxtasoftware.dao.AlignmentDao;
import org.juxtasoftware.dao.CacheDao;
import org.juxtasoftware.dao.ComparisonSetDao;
import org.juxtasoftware.dao.WitnessDao;
import org.juxtasoftware.model.Alignment;
import org.juxtasoftware.model.Alignment.AlignedAnnotation;
import org.juxtasoftware.model.AlignmentConstraint;
import org.juxtasoftware.model.ComparisonSet;
import org.juxtasoftware.model.QNameFilter;
import org.juxtasoftware.model.Witness;
import org.juxtasoftware.util.BackgroundTask;
import org.juxtasoftware.util.BackgroundTaskCanceledException;
import org.juxtasoftware.util.BackgroundTaskStatus;
import org.juxtasoftware.util.QNameFilters;
import org.juxtasoftware.util.TaskManager;
import org.restlet.data.Encoding;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.engine.application.EncodeRepresentation;
import org.restlet.representation.ReaderRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import eu.interedition.text.Range;

/**
 * Resource used to GET a json object containing histogram
 * data for a comparison set
 * 
 * @author loufoster
 *
 */
public class HistogramResource extends BaseResource {
    @Autowired private ComparisonSetDao setDao;
    @Autowired private QNameFilters filters;
    @Autowired private AlignmentDao alignmentDao;
    @Autowired private CacheDao cacheDao;
    @Autowired private WitnessDao witnessDao;
    @Autowired private TaskManager taskManager;
    @Autowired private Integer averageAlignmentSize;
    
    private ComparisonSet set;
    private Witness baseWitness;
    private List<Long> witnessIdList;
    
    protected static final Logger LOG = LoggerFactory.getLogger( Constants.WS_LOGGER_NAME );
    
    @Override
    protected void doInit() throws ResourceException {

        super.doInit();
        
        Long id = getIdFromAttributes("id");
        if ( id == null ) {
            return;
        }
        this.set = this.setDao.find(id);
        if (validateModel(this.set) == false) {
            return;
        }
        
        // Get the required base witness ID
        if (getQuery().getValuesMap().containsKey("base") == false ) {
            setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Missing base parameter");
        } else {
            String baseIdStr = getQuery().getValues("base");
            
            Long baseId = null;
            try {
                baseId = Long.parseLong(baseIdStr);
            } catch (NumberFormatException e) {
                setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Invalid base witness id");
                return;
            }
            this.baseWitness = this.witnessDao.find( baseId);
            if ( validateModel( this.baseWitness) == false ) {
                return;
            }
        }
        
        // grab the witness id filter. If provided, only these witnesses
        // will be included in the histogram.
        this.witnessIdList = new ArrayList<Long>();
        if (getQuery().getValuesMap().containsKey("docs")  ) {
            String[] docStrIds = getQuery().getValues("docs").split(",");
            for ( int i=0; i<docStrIds.length; i++ ) {
                try {
                    Long witId = Long.parseLong(docStrIds[i]);
                    if ( id.equals(this.baseWitness.getId()) == false ) {
                        this.witnessIdList.add( witId );
                    }
                } catch (Exception e ) {
                    setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Invalid document id specified");
                    return;
                }
            }
        }
    }
    
    @Get("json")
    public Representation toJson() throws IOException {
        // Create the info block that identifies this vsualization
        HistogramInfo info = new HistogramInfo(this.set, this.baseWitness, this.witnessIdList);
        
        // FIRST, see if the cached version is available:
        LOG.info("Is histogram cached: "+info);
        if ( this.cacheDao.histogramExists(this.set.getId(), info.getKey())) {
            LOG.info("Retrieving cached histogram");
            Representation rep = new ReaderRepresentation( 
                this.cacheDao.getHistogram(this.set.getId(), info.getKey()), 
                MediaType.APPLICATION_JSON);
            if ( isZipSupported() ) {
                return new EncodeRepresentation(Encoding.GZIP, rep);
            } else {
                return rep;
            }
        }
                
        // set up a filter to get the annotations necessary for this histogram
        QNameFilter changesFilter = this.filters.getDifferencesFilter();
        AlignmentConstraint constraints = new AlignmentConstraint(this.set, this.baseWitness.getId());
        for ( Long id : this.witnessIdList ) {
            constraints.addWitnessIdFilter(id);
        }
        constraints.setFilter(changesFilter);
        
        // Get the number of annotations that will be returned and do a rough calcuation
        // to see if generating this histogram will exhaust available memory - with a 5M pad
        final Long count = this.alignmentDao.count(constraints);
        final long estimatedByteUsage = count*this.averageAlignmentSize + this.baseWitness.getText().getLength();
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        LOG.info("HISTOGRAM ["+ estimatedByteUsage+"] ESTIMATED USAGE");
        LOG.info("HISTOGRAM ["+ Runtime.getRuntime().freeMemory()+"] ESTIMATED FREE");
        if (estimatedByteUsage > Runtime.getRuntime().freeMemory()) {
            setStatus(Status.SERVER_ERROR_INSUFFICIENT_STORAGE);
            return toTextRepresentation(
                "The server has insufficent resources to generate a histogram for this collation.");
        }
        
        final String taskId =  generateTaskId(set.getId(), baseWitness.getId() );
        if ( this.taskManager.exists(taskId) == false ) {
            HistogramTask task = new HistogramTask(taskId, info);
            this.taskManager.submit(task);
        } 
        return toJsonRepresentation( "{\"status\": \"RENDERING\", \"taskId\": \""+taskId+"\"}" );
    }
    
    private void render(HistogramInfo histogramInfo) throws IOException {

        // Get all of the differences and sort them
        QNameFilter changesFilter = this.filters.getDifferencesFilter();
        AlignmentConstraint constraints = new AlignmentConstraint( histogramInfo.set, histogramInfo.base.getId());
        for ( Long id : histogramInfo.witnesses ) {
            constraints.addWitnessIdFilter(id);
        }
        constraints.setFilter(changesFilter);
        List<Alignment> diffs =  this.alignmentDao.list(constraints);
        Collections.sort(diffs, new Comparator<Alignment>() {
            @Override
            public int compare(Alignment a, Alignment b) {
                Range r1 = a.getWitnessAnnotation(baseWitness.getId()).getRange();
                Range r2 = b.getWitnessAnnotation(baseWitness.getId()).getRange();
                if ( r1.getStart() < r2.getStart() ) {
                    return -1;
                } else if ( r1.getStart() > r2.getStart() ) {
                    return 1;
                } else {
                    if ( r1.getEnd() < r2.getEnd() ) {
                        return -1;
                    } else if ( r1.getEnd() > r2.getEnd() ) {
                        return 1;
                    } 
                }
                return 0;
            }
        });
        
        // run thru the base document length in 1% chunks
        int[] histogram = createHistogram( );
        boolean[] addTracker = createAddTracker();
        double baseLen = this.baseWitness.getText().getLength();
        long firstCharInRange = 0;
        for ( int percent=1; percent<=100; percent++) {
            long lastCharInRange = Math.round( baseLen*(percent*0.01) );
            Range docRange = new Range(firstCharInRange, lastCharInRange);
            Iterator<Alignment> diffItr = diffs.iterator();
            while ( diffItr.hasNext() ) {
                Alignment diff = diffItr.next();
                AlignedAnnotation anno = diff.getWitnessAnnotation(this.baseWitness.getId());
                Range baseRange = anno.getRange();
                
                // don't use interediton range checks: they break down for 0-length ranges
                if ( docRange.getStart() <= baseRange.getEnd() && baseRange.getStart() <= docRange.getEnd() ) {
                    
                    // track adds separately from other change
                    if ( baseRange.length() == 0 && diff.getEditDistance() == -1 ) {
                        addTracker[percent-1] = true;
                    } else {
                        histogram[percent-1]++;
                    }
                    
                    diffItr.remove();                    
                } else {
                    break;
                }
            }
            
            firstCharInRange = lastCharInRange;
            if ( diffs.size() == 0 ) {
                break;
            }
        }        

        
        int maxVal = -1;
        for ( int i=0; i<100; i++ ){
            if (histogram[i] > maxVal ) {
                maxVal = histogram[i];
            }
        }

        // scale to max value and dump to temp file
        File hist = File.createTempFile("histo", "data");
        hist.deleteOnExit();
        BufferedWriter bw = new BufferedWriter( new FileWriterWithEncoding(hist, "UTF-8") );
        try {
            boolean firstWrite = true;
            bw.write( "{\"baseName\": \""+this.baseWitness.getJsonName()+"\", \"histogram\": [" );
            for ( int i=0;i<histogram.length;i++) {
                if ( firstWrite == false ) {
                    bw.write(",");
                }
                firstWrite = false;
                if ( maxVal > 0 ) {
                    double val = (double)histogram[i];
                    double scaled = val/(double)maxVal;
                    if ( addTracker[i] ) {
                        scaled += 0.025;
                    }
                    if ( scaled > 1.0 ) {
                        scaled = 1.0;
                    }
                    bw.write(String.format("%1.2f",  scaled));
                } else {
                    if ( addTracker[i] ) {
                        bw.write( "0.025");
                    } else {
                        bw.write( "0.00");
                    }
                }
            }
            bw.write("] }");
        } catch (IOException e) {
            LOG.error("Unable to generate histogram", e);
            throw e;
        } finally {
            IOUtils.closeQuietly(bw);
        }
        
//        Reader r = new FileReader(hist);
//        String foo = IOUtils.toString(r);
//        System.err.println(foo);
//        return toJsonRepresentation( foo );
        
        // cache the results and kill temp file
        LOG.info("Cache histogram "+histogramInfo);
        FileReader r = new FileReader(hist);
        this.cacheDao.cacheHistogram(this.set.getId(), histogramInfo.getKey(), r);
        IOUtils.closeQuietly(r);
        hist.delete();
    }
    
    
    
    
    private String generateTaskId( final Long setId, final Long baseId) {
        final int prime = 31;
        int result = 1;
        result = prime * result + setId.hashCode();
        result = prime * result + baseId.hashCode();
        return "histogram-"+result;
    }
    
    /**
     * create and init histogram array with all zeros
     * @param size
     * @return
     */
    private int[] createHistogram() {
        int[] out = new int[100];
        for ( int i=0; i<100; i++) {
            out[i] = 0;
        }
        return out;
    }
    private boolean[] createAddTracker() {
        boolean[] out = new boolean[100];
        for ( int i=0; i<100; i++) {
            out[i] = false;
        }
        return out;
    }
    
    /**
     * Data used to generate the histogram
     * @author loufoster
     *
     */
    public static class HistogramInfo {
        public final ComparisonSet set;        
        public final Witness base;
        public final List<Long> witnesses = new ArrayList<Long>();
        
        public HistogramInfo(ComparisonSet set, Witness base, List<Long> witnesses ) {
            this.base = base;
            this.witnesses.addAll(witnesses);
            this.set = set;
        }
        
        public long getKey() {
            return (long)hashCode();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((base == null) ? 0 : base.hashCode());
            result = prime * result + ((set == null) ? 0 : set.hashCode());
            result = prime * result + ((witnesses == null) ? 0 : witnesses.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            HistogramInfo other = (HistogramInfo) obj;
            if (base == null) {
                if (other.base != null)
                    return false;
            } else if (!base.equals(other.base))
                return false;
            if (set == null) {
                if (other.set != null)
                    return false;
            } else if (!set.equals(other.set))
                return false;
            if (witnesses == null) {
                if (other.witnesses != null)
                    return false;
            } else if (!witnesses.equals(other.witnesses))
                return false;
            return true;
        }
        
        @Override
        public String toString() {
            return "HistogramInfo [set=" + set + ", base=" + base + ", witnesses=" + witnesses + "] = KEY: " +getKey();
        }
    }
    
    /**
     * Task to asynchronously render the visualization
     */
    private class HistogramTask implements BackgroundTask {
        private final String name;
        private final HistogramInfo histogramInfo;
        private BackgroundTaskStatus status;
        private Date startDate;
        private Date endDate;
        
        public HistogramTask(final String name, HistogramInfo info) {
            this.name =  name;
            this.status = new BackgroundTaskStatus( this.name );
            this.startDate = new Date();
            histogramInfo = info;
        }
        
        @Override
        public Type getType() {
            return BackgroundTask.Type.VISUALIZE;
        }
        
        @Override
        public void run() {
            try {
                LOG.info("Begin task "+this.name);
                this.status.begin();
                HistogramResource.this.render( this.histogramInfo );
                LOG.info("Task "+this.name+" COMPLETE");
                this.endDate = new Date();   
                this.status.finish();
            } catch (IOException e) {
                LOG.error(this.name+" task failed", e.toString());
                this.status.fail(e.toString());
                this.endDate = new Date();
            } catch ( BackgroundTaskCanceledException e) {
                LOG.info( this.name+" task was canceled");
                this.endDate = new Date();
            } catch (Exception e) {
                LOG.error(this.name+" task failed", e);
                this.status.fail(e.toString());
                this.endDate = new Date();       
            }
        }
        
        @Override
        public void cancel() {
            this.status.cancel();
        }

        @Override
        public BackgroundTaskStatus.Status getStatus() {
            return this.status.getStatus();
        }

        @Override
        public String getName() {
            return this.name;
        }
        
        @Override
        public Date getEndTime() {
            return this.endDate;
        }
        
        @Override
        public Date getStartTime() {
            return this.startDate;
        }
        
        @Override
        public String getMessage() {
            return this.status.getNote();
        }
    }
}
