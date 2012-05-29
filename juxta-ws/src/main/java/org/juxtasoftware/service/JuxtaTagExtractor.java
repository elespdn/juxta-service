package org.juxtasoftware.service;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.juxtasoftware.Constants;
import org.juxtasoftware.model.JuxtaXslt;
import org.juxtasoftware.model.Note;
import org.juxtasoftware.model.PageBreak;
import org.juxtasoftware.service.importer.jxt.Util;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.google.common.collect.Maps;

import eu.interedition.text.Name;
import eu.interedition.text.Range;

/**
 * JuxtaExtractor is a SAX xml parser that will collect the position information
 * for tags that require special handling by Juxta. Notes & Page Breaks
 * @author loufoster
 */
public class JuxtaTagExtractor extends DefaultHandler  {
    private Note currNote = null;
    private StringBuilder currNoteContent;
    private List<Note> notes = new ArrayList<Note>();
    private List<PageBreak> breaks = new ArrayList<PageBreak>();
    private Map<String, Range> identifiedRanges = Maps.newHashMap();
    private Map<String,Integer> tagOccurences = Maps.newHashMap();
    private JuxtaXslt xslt;     
    private Long witnessId;
    private long currPos = 0;
    private boolean isExcluding = false;
    private Stack<String> exclusionContext = new Stack<String>();
    private Stack<String> xmlIdStack = new Stack<String>();
    private Stack<Long> revisionStartOffsetStack = new Stack<Long>();
    private List<RevisionRange> revisions = new ArrayList<RevisionRange>();
    
    public void setWitnessId( final Long witnessId ) {
        this.witnessId = witnessId;
    }

    public void extract(final Reader sourceReader, final JuxtaXslt xslt) throws SAXException, IOException {          
        this.xslt = xslt;
        Util.saxParser().parse( new InputSource(sourceReader), this);
    }
    
    public List<RevisionRange> getRevisions() {
        return this.revisions;
    }
    public List<Note> getNotes() {
        return this.notes;
    }
    public List<PageBreak> getPageBreaks() {
        return this.breaks;
    }
    
    private boolean isRevision(final String qName ) {
        return ( qName.equals("add") || qName.equals("addSpan") ||
                 qName.equals("del") || qName.equals("delSpan"));
    }
    
    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        // always count up the number of occurrences for this tag
        countOccurrences(qName);
        
        if ( this.isExcluding ) {
            this.exclusionContext.push(qName);
            return;
        }
        
        if ( isSpecialTag(qName) == false && this.xslt.isExcluded(qName, this.tagOccurences.get(qName))) {
            this.isExcluding = true;
            this.exclusionContext.push(qName);
            System.err.println(qName+"["+this.tagOccurences.get(qName)+"] is excluded");
        }
                    
        if ( qName.equals("note") ) {
            handleNote(attributes);
        } else if (qName.equals("pb") ) {
            handlePageBreak(attributes);
        } else {
            final String idVal = getIdValue(attributes);
            if ( idVal != null ) {
                this.identifiedRanges.put(idVal, new Range(this.currPos, this.currPos));
                this.xmlIdStack.push(idVal);
            } else  {
                this.xmlIdStack.push("NA");
            }
            
            if ( isRevision(qName)) {
               this.revisionStartOffsetStack.push( this.currPos );
            }
        }
    }
    
    
    private void countOccurrences(String qName) {
        Integer cnt = this.tagOccurences.get(qName);
        if ( cnt == null ) {
            this.tagOccurences.put(qName, 1);
        } else {
            this.tagOccurences.put(qName, cnt+1);
        }
    }

    private boolean isSpecialTag( final String qName ) { 
        return (qName.equals("note") || qName.equals("pb"));
    }
    
    private void handleNote(Attributes attributes) {
        this.currNote = new Note();
        this.currNote.setWitnessId( this.witnessId );
        this.currNote.setAnchorRange(new Range(this.currPos, this.currPos));
        this.currNoteContent = new StringBuilder();

        // search note tag attributes for type and target and add them to the note.
        for (int idx = 0; idx<attributes.getLength(); idx++) {  
            String name = attributes.getQName(idx);
            if ( name.contains(":")) {
                name = name.split(":")[1];
            }
            if ("type".equals(name)) {
                this.currNote.setType(attributes.getValue(idx));
            } else if ("target".equals(name)) {
                this.currNote.setTargetID(attributes.getValue(idx));
            }
        }
        this.notes.add(this.currNote);
    }

    private void handlePageBreak(Attributes attributes) {
        PageBreak pb = new PageBreak();
        pb.setWitnessId(this.witnessId);
        pb.setOffset(this.currPos);
        
        for (int idx = 0; idx<attributes.getLength(); idx++) {  
            String name = attributes.getQName(idx);
            if ( name.contains(":")) {
                name = name.split(":")[1];
            }
            if ("n".equals(name)) {
                pb.setLabel( attributes.getValue(idx) );
            } 
        }
        this.breaks.add(pb);
    }

    private String getIdValue( Attributes attributes ){
        for (int idx = 0; idx<attributes.getLength(); idx++) {  
            String val = attributes.getQName(idx);
            if ( val.contains(":")) {
                val = val.split(":")[1];
            }
            if ( val.equals("id")) {
                return attributes.getValue(idx);
            }
        }
        return null;
    }
    
    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if ( this.isExcluding ) {
            this.exclusionContext.pop();
            this.isExcluding = !this.exclusionContext.empty();
            return;
        }
        
        if (qName.equals("note")) {
            this.currNote.setContent(this.currNoteContent.toString().replaceAll("\\s+", " ").trim());
            if ( this.currNote.getContent().length() == 0 ) {
                this.notes.remove(this.currNote);
            }
            this.currNote = null;
            this.currNoteContent = null;
        } else if (qName.equals("pb")) {
            // pagebreaks always include a linebreak. add 1 to
            // current position to account for this
            if ( this.currNote == null ) {
                this.currPos++;
            }
        } else {
            // if the tag has an identifier, save it off for crossreference with targeted notes
            if ( this.xmlIdStack.empty() == false ) {
                final String xmlId = this.xmlIdStack.pop();
                if (xmlId.equals("NA") == false ) {
                    this.identifiedRanges.put(xmlId, new Range(this.identifiedRanges.get(xmlId).getStart(), this.currPos));
                }
            }
            
            // if this tag is in the midst of a note, check it for 
            // linebreaks and add a hard break now. Also, do NOT
            // increment position count if we are collecting a note.
            if ( this.currNote != null ) {
                if ( this.xslt.hasLineBreak(qName) ){ 
                    this.currNoteContent.append("<br/>");
                }
            } else  if ( this.xslt.hasLineBreak(qName) ){
                this.currPos++;
            }
            
            // lastly, wrap up any annotations on a revision
            if ( isRevision(qName)) {
                final Range range = new Range(this.revisionStartOffsetStack.pop(), this.currPos);
                this.revisions.add( new RevisionRange(qName, range) );
            }
        }            
    }
    
    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if ( this.isExcluding == false ) {
            String txt = new String(ch, start, length);
            
            // remove last newline and trailing space (right trim)
            txt = txt.replaceAll("[\\n]\\s*$", "");
            
            // remove first newline and traiing whitespace.
            // this will leave any leading whitespace before the 1st newline
            txt = txt.replaceAll("[\\n]\\s*", "");

            //System.err.println("["+txt+"]");
            if ( this.currNote != null ) {
                this.currNoteContent.append(txt);
            } else {
                this.currPos += txt.length();
            }
        }
    }
    
    @Override
    public void endDocument() throws SAXException {
        // at the end of parsing, find all notes that have a target
        // specified. Look up that id and set the associated range
        // as the note anchor point
        for ( Note note : this.notes ) {
            String noteTargetId = note.getTargetID();
            if ( noteTargetId != null && noteTargetId.length() > 0){
                Range tgtRange = this.identifiedRanges.get(noteTargetId);
                if ( tgtRange != null ) {
                    note.setAnchorRange( tgtRange );
                }
            }
        }
    }
    
    public static class RevisionRange {
        private final Name name;
        private final Range range;
        private RevisionRange( final String qName, final Range r) {
            if ( qName.equals("add")) {
                this.name = Constants.TEI_ADD;
            } else if ( qName.equals("addSpan")) {
                this.name = Constants.TEI_ADD_SPAN;
            } else if ( qName.equals("del")) {
                this.name = Constants.TEI_DEL;
            } else if ( qName.equals("delSpan")) {
                this.name = Constants.TEI_DEL_SPAN;
            } else {
                throw new RuntimeException("Illegal revision tag name: " + qName);
            }
            this.range = new Range(r);
        }
        
        public Name getName() {
            return this.name;
        }
        
        public Range getRange() {
            return this.range;
        }
    }
}   