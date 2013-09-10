/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version. 
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/

package ddf.services.schematron;

import java.io.Serializable;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Vector;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.URIResolver;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.Configuration;
import net.sf.saxon.TransformerFactoryImpl;
import net.sf.saxon.trans.DynamicLoader;

import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ddf.catalog.data.Metacard;
import ddf.catalog.operation.CreateRequest;
import ddf.catalog.operation.DeleteRequest;
import ddf.catalog.operation.UpdateRequest;
import ddf.catalog.plugin.PreIngestPlugin;
import ddf.catalog.plugin.StopProcessingException;
import ddf.catalog.validation.ValidationException;
import ddf.catalog.validation.MetacardValidator;


/**
 * This pre-ingest service provides validation of an ingested XML document against a Schematron schema file.
 * 
 * When this service is instantiated at deployment time to the OSGi container it goes through 3 different preprocessing
 * stages on the Schematron schema file. (These steps are required by the ISO Schematron implementation)
 * <ol>
 *   <li>1. Preprocess the Schematron schema with iso_dsdl_include.xsl. 
 *   This is a macro processor to assemble the schema from various parts.</li>
 *   <li>2. Preprocess the output from stage 1 with iso_abstract_expand.xsl. 
 *   This is a macro processor to convert abstract patterns to real patterns.</li>
 *   <li>3. Compile the Schematron schema into an XSLT script.
 *   This will use iso_svrl_for_xslt2.xsl (which in turn invokes iso_schematron_skeleton_for_saxon.xsl)</li>
 * </ol>
 *  
 *  When XML documents are ingested, this service will run the XSLT generated by stage 3 
 *  against the XML document, validating it against the "compiled" Schematron schema file.
 *  
 *  This service is using the SVRL script, hence the output of the validation will be an SVRL-formatted XML document.
 *  
 *  @see <a href="http://www.schematron.com">Schematron</a>
 *  
 * @author rodgersh
 *
 */ 
public class SchematronValidationService implements PreIngestPlugin, MetacardValidator
{
	/** The original Schematron .sch file */
	private String schematronSchemaFilename;
    
    /** 
     * Priority of this service using this Schematron .sch ruleset file. 
     * This is retrieved by LocalSite implementation to determine the order of
     * execution of the PreIngest services.
     */
    private int priority;
    
    /** 
     * Flag indicating if Schematron warnings should be suppressed, meaning that if only warnings are
     * detected during validation, then Catalog Entry is considered valid.
     */
    private boolean suppressWarnings;
    
    /** 
     * Saxon transformer factory. The Saxon TransformerFactory is specified to JAXP via
     * the META-INF/services/javax.xml.transform.TransformerFactory file. 
     */
    private TransformerFactory transformerFactory;
	
	/** The compiled Schematron schema file used to validate the input XML document */
	private Templates validator;
	
	/** Generated xsl:messages from the preprocessor */
	private Vector<String> warnings = new Vector<String>();
	
	/** Report generated during transformation/validation of input XML against precompiled .sch file */
	private SchematronReport report;
	
	/** This class' logger */
    Logger LOGGER = LoggerFactory.getLogger( SchematronValidationService.class );

	private static final int DEFAULT_PRIORITY = 100;
    
    private static final String CLASS_NAME = SchematronValidationService.class.getName();
    
    /** ISO Schematron XSLT to expand inclusions in provided Schematron schema file */
    private static final String ISO_SCHEMATRON_INCLUSION_EXPAND_XSL_FILENAME = "iso-schematron/iso_dsdl_include.xsl";
    
    /** ISO Schematron XSLT to expand abstractions in provided Schematron schema file */
    private static final String ISO_SCHEMATRON_ABSTRACT_EXPAND_XSL_FILENAME = "iso-schematron/iso_abstract_expand.xsl";
    
    /** SVRL extension to ISO Schematron skeleton, allowing an XML-formatted report output from Schematron validation */
    private static final String ISO_SVRL_XSL_FILENAME = "iso-schematron/iso_svrl_for_xslt2.xsl";
    
   
    /**
     * @param bundle OSGi bundle containing sch file that will be using this service
     * @param schematronSchemaFilename client-provided Schematron rules in XML-format, usually with a .sch file extension
     * 
     * @throws SchematronInitializationException
     */
    public SchematronValidationService( final Bundle bundle, String schematronSchemaFilename )
        throws SchematronInitializationException
    {
    	this( bundle, schematronSchemaFilename, false );
    }
    
    
    /**
     * @param bundle OSGi bundle containing sch file that will be using this service
     * @param schematronSchemaFilename client-provided Schematron rules in XML-format, usually with a .sch file extension
     * @param suppressWarnings indicates whether to suppress Schematron validation warnings and indicate that a Catalog Entry
     * with only warnings is valid
     * 
     * @throws SchematronInitializationException
     */
    public SchematronValidationService( final Bundle bundle, String schematronSchemaFilename, boolean suppressWarnings )
        throws SchematronInitializationException
    {
    	String methodName = "constructor";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	LOGGER.debug( "schematronSchemaFilename = " + schematronSchemaFilename );
    	LOGGER.debug( "suppressWarnings = " + suppressWarnings );
    	
    	this.schematronSchemaFilename = schematronSchemaFilename;
    	this.suppressWarnings = suppressWarnings;
    	this.priority = DEFAULT_PRIORITY;
    	
    	init( bundle, schematronSchemaFilename );
    	
    	LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
    }
    
    
    /**
     * @param bundle OSGi bundle containing sch file that will be using this service
     * @param schematronSchemaFilename
     * 
     * @throws SchematronInitializationException
     */
    private void init( final Bundle bundle, String schematronSchemaFilename )
        throws SchematronInitializationException
    {
    	String methodName = "init";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	
    	// Initialize TransformerFactory if not already done
		if ( transformerFactory == null ) 
		{
			transformerFactory = TransformerFactory.newInstance( net.sf.saxon.TransformerFactoryImpl.class.getName(),
			    SchematronValidationService.class.getClassLoader() );
		}

		// Build the URI resolver to resolve any address for those who call base XSLTs from extension bundles
		// (i.e., bundles other than the one we are currently running in)
		try 
		{
			URIResolver resolver = new URIResolver() 
			{
				@Override
				public Source resolve( String href, String base ) throws TransformerException 
				{
					LOGGER.debug( "URIResolver:  href = " + href + ",   base = " + base );
					
					// If href starts with "./" strip it off because the bundle class loader does not
					// know how to handle this prefix
					if ( href.startsWith( "./" ) )
					{
						href = href.substring( "./".length() );
						LOGGER.debug( "URIResolver:  (Modified) href = " + href );
					}
					
					try 
					{
						URL resourceAddressURL = bundle.getResource(href);
						String resourceAddress = resourceAddressURL.toString();
						LOGGER.debug( "Resolved resource address:" + resourceAddress );

						return new StreamSource( resourceAddress );
					} 
					catch (Exception e) 
					{
						LOGGER.error( "URIResolver error: " + e.getMessage() );
						return null ;
					}
				}
			};

			transformerFactory.setURIResolver( resolver );
			
			// Use Saxon-specific Configuration class to setup a dynamic class loader so we can search across
			// bundles for imported XSLTs
			Configuration config = ( (TransformerFactoryImpl) transformerFactory ).getConfiguration();
			DynamicLoader dynamicLoader = new DynamicLoader();
			dynamicLoader.setClassLoader( new BundleProxyClassLoader( bundle ) );
			config.setDynamicLoader( dynamicLoader );

			// Retrieve the Schematron schema XML file (usually a .sch file)
			URL schUrl = bundle.getResource( schematronSchemaFilename );
			Source schSource = new StreamSource( schUrl.toString() );

			// Stage 1: Perform inclusion expansion on Schematron schema file
			DOMResult stage1Result = performStage( bundle, schSource, ISO_SCHEMATRON_INCLUSION_EXPAND_XSL_FILENAME );
			DOMSource stage1Output = new DOMSource( stage1Result.getNode() );
			
			// Stage 2: Perform abstract expansion on output file from Stage 1
			DOMResult stage2Result = performStage( bundle, stage1Output, ISO_SCHEMATRON_ABSTRACT_EXPAND_XSL_FILENAME );
			DOMSource stage2Output = new DOMSource( stage2Result.getNode() );
			
			// Stage 3: Compile the .sch rules that have been prepocessed by Stages 1 and 2 (i.e., the output of Stage 2)
			DOMResult stage3Result = performStage( bundle, stage2Output, ISO_SVRL_XSL_FILENAME );
			DOMSource stage3Output = new DOMSource( stage3Result.getNode() );
			
			// Precompile the Schematron preprocessor XSL file
			this.validator = transformerFactory.newTemplates( stage3Output );
		} 
		catch ( TransformerConfigurationException e ) 
		{
			LOGGER.error( "Couldn't create transfomer", e );
			throw new SchematronInitializationException( "Error trying to create SchematronValidationService using sch file " + this.schematronSchemaFilename, e );
		} 
		catch ( TransformerException e ) 
		{
			LOGGER.error( "Couldn't create transfomer", e );
			throw new SchematronInitializationException( "Error trying to create SchematronValidationService using sch file " + this.schematronSchemaFilename, e );
		} 
		catch ( ParserConfigurationException e ) 
		{
			LOGGER.error( "Couldn't create transfomer", e );
			throw new SchematronInitializationException( "Error trying to create SchematronValidationService using sch file " + this.schematronSchemaFilename, e );
		}
		
		// Would go here if an invalid .sch file was passed in
		catch ( Exception e ) 
		{
			LOGGER.error( "Couldn't create transfomer", e );
			throw new SchematronInitializationException( "Error trying to create SchematronValidationService using sch file " + this.schematronSchemaFilename, e );
		}
		
		LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
    }
    
    
    /**
     * Execute a Schematron preprocessing/compile stage on the provided input source using the
     * provided preprocessor file.
     * 
     * @param bundle OSGi bundle
     * @param input name of file to be transformed
     * @param preprocessorFilename name of preprocessor file to use for the transformation
     * 
     * @return result of transforming the preprocessor file into a DOMResult
     * @throws TransformerException
     * @throws TransformerConfigurationException
     * @throws ParserConfigurationException
     */
    private DOMResult performStage( final Bundle bundle, Source input, String preprocessorFilename )
		throws TransformerException, TransformerConfigurationException, ParserConfigurationException, SchematronInitializationException
	{
    	String methodName = "performStage";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	LOGGER.debug( "preprocessorFilename = " + preprocessorFilename );
    	
		// Retrieve the preprocessor XSL file
		URL preprocessorUrl = bundle.getResource( preprocessorFilename );
		if ( preprocessorUrl == null ) 
		{
			LOGGER.debug( "preprocessorUrl is NULL - cannot perform staging of Schematron preprocessor file" );
			throw new SchematronInitializationException( "preprocessorUrl is NULL for file " + preprocessorFilename + " - cannot perform staging of Schematron preprocessor file" );
		}
		else
		{
			LOGGER.debug( "URL = " + preprocessorUrl.toString() );
		}
		Source preprocessorSource = new StreamSource( preprocessorUrl.toString() );

		// Initialize container for warnings we may receive during transformation of input
		warnings = new Vector<String>();
		
		// Create a transformer for this preprocessor
		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer( preprocessorSource );
		
		// Setup an error listener to catch warnings and errors generated during transformation
		Listener listener = new Listener();
		transformer.setErrorListener( listener );
		
		// Transform the input using the preprocessor's transformer, capturing the output in a DOM
		DOMResult domResult = new DOMResult();
		transformer.transform( input, domResult );
		
		LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
		
		return domResult;
	}  
    
    
    /*
    * (non-Javadoc)
    * @see ddf.catalog.plugin.PreIngestPlugin#process(ddf.catalog.operation.CreateRequest)
     */
    @Override
    public CreateRequest process( CreateRequest create )
	    throws StopProcessingException 
	{
    	String methodName = "processCreate";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	
    	if(create == null)
    	{
    		throw new StopProcessingException("Null createRequest");
    	}
		validateEntryList( create.getMetacards() );
		
		LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
		
		return create;
    }

    
   /*
    * (non-Javadoc)
    * @see ddf.catalog.plugin.PreIngestPlugin#process(ddf.catalog.operation.UpdateRequest)
     */
    @Override
    public UpdateRequest process ( UpdateRequest update )
	    throws StopProcessingException 
	{
    	String methodName = "processUpdate";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	if(update == null)
    	{
    		throw new StopProcessingException("Null updateRequest");
    	}
    	
    	List<Entry<Serializable, Metacard>> list = update.getUpdates();
    	if(list != null)
    	{
            List<Metacard> requestMetacards = new ArrayList<Metacard>();
            for ( Entry<Serializable,Metacard> updateEntry : list ) {
                requestMetacards.add( updateEntry.getValue() );
            }    	
            validateEntryList(requestMetacards );
    	}
		LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
		
		return update;
    }

    
   /*
     */
    @Override
    public DeleteRequest process( DeleteRequest delete )
	{
    	String methodName = "processDelete";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	
		// no validation to perform
		return delete;
    }
    
    
    /**
     * Extract the XML metadata from each CatalogEntry in the provided list and perform
     * Schematron validation on it.
     * 
     * @param entries list of CatalogEntry objects to validate
     * 
     * @throws StopProcessingException
     */
    private void validateEntryList( List<Metacard> entries )
	    throws StopProcessingException 
	{
    	String methodName = "validateEntryList";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	
		Iterator<Metacard> iter = entries.iterator();
		Metacard curEntry;
		
		// Count of number of catalog entries we validate. This will be used to identify which
		// catalog entry number has validation issues, if any.
		int catalogEntryNum = 0;
		while ( iter.hasNext() ) 
		{
		    curEntry = iter.next();
		    catalogEntryNum++;
		    performSchematronValidation( catalogEntryNum, curEntry );
		}
		
		LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
    }

    
    /**
     * Perform Schematron validation on specified catalog entry.
     * 
     * @param number of the catalog entry being validated (ranges from 1 to n)
     * @param catalogEntry catalog entry to be validated
     * 
     * @throws StopProcessingException
     */
    public void performSchematronValidation( int catalogEntryNum, Metacard catalogEntry ) throws StopProcessingException 
    {
    	String methodName = "performSchematronValidation";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	
    	LOGGER.debug( "Using .sch ruleset: " + this.schematronSchemaFilename );
    	
    	// Convert the catalog entry's Document to a String
		String entryDocument = catalogEntry.getMetadata();
		LOGGER.debug("entryDocument: " + entryDocument);
		
		// Create a Reader for the catalog entry's contents
		StringReader entryDocumentReader = new StringReader( entryDocument );

		try 
		{
			// Using the precompiled/stored Schematron validator, validate the catalog entry's contents
			Transformer transformer = validator.newTransformer();
			DOMResult schematronResult = new DOMResult();
			transformer.transform( new StreamSource( entryDocumentReader ), schematronResult );
			this.report = new SvrlReport( schematronResult );

			LOGGER.trace( "SVRL Report:\n\n" + report.getReportAsText() );
			
			// If the Schematron validation failed, then throw an exception with details of the errors
			// and warnings from the Schematron report included in the exception that is thrown to the client.
			if ( !this.report.isValid( this.suppressWarnings ) )
			{
				StringBuffer errorMessage =  new StringBuffer( "Schematron validation failed for catalog entry #" + catalogEntryNum + ".\n\n" );
				List<String> errors = this.report.getErrors();
				LOGGER.debug( "errors.size() = " + errors.size() );
				for ( String error : errors )
				{
					errorMessage.append( error );
					errorMessage.append( "\n" );
				}
				
				// If warnings are to be included from the Schematron report as part of the errors message
				if ( !this.suppressWarnings )
				{
					List<String> warnings = this.report.getWarnings();
					LOGGER.debug( "warnings.size() = " + warnings.size() );
					for ( String warning : warnings )
					{
						LOGGER.debug( "warning = " + warning );
						errorMessage.append( warning );
						errorMessage.append( "\n" );
					}
					
				}
				throw new StopProcessingException( errorMessage.toString() );
			}

		} 
		catch ( TransformerException te ) 
		{
			LOGGER.debug("Unable to setup validator", te);
			LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
			
		    throw new StopProcessingException( "Could not setup validator to perform validation." );
		}
		
		LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
    }
    
    
   
    
    /**
     * Retrieve the Schematron validation results.
     * 
     * @return Schematron validation output report
     */
    public SchematronReport getSchematronReport()
    {
    	return this.report;
    }
    
    
    /**
     * Retrieve the name of the original Schematron .sch file provided as input to this
     * validation service.
     * 
     * @return name of original Schematron .sch file
     */
    public String getSchematronSchemaFilename()
    {
    	return this.schematronSchemaFilename;
    }
    
    
    /**
     * Suppress Schematron warnings, such that only errors mark a request as invalid.
     * This method is called whenever the Save button is selected for a Schematron ruleset bundle
     * on the OSGi container's web console Configuration admin page.
     * 
     * @param suppressWarnings true indicates Schematron warnings are to be suppressed
     */
    public void setSuppressWarnings( boolean suppressWarnings )
    {
    	LOGGER.debug( "ENTERING: setSuppressWarnings" );
    	LOGGER.debug( "suppressWarnings = " + suppressWarnings + "(sch filename = " + this.schematronSchemaFilename +")" );
    	
    	this.suppressWarnings = suppressWarnings;
    	
    	LOGGER.debug( "EXITING: setSuppressWarnings" );
    }
    
    
    /**
     * Retrieve suppress warnings flag.
     * 
     * @return true indicates Schematron warnings are being suppressed
     */
    public boolean getSuppressWarnings()
    {
    	LOGGER.debug( "ENTERING: getSuppressWarnings" );
    	
    	return this.suppressWarnings;
    }
    
    
    /**
     * Sets the priority of this Schematron Validation Service, which indicates its order
     * of execution amongst all preingest services.
     * 
     * @param priority priority of service, ranging from 1 to 100 (1 being highest priority)
     */
    public void setPriority( int priority )
    {
    	String methodName = "setPriority";
    	LOGGER.debug( "ENTERING: " + CLASS_NAME + "." + methodName );
    	LOGGER.debug( "Setting priority = " + priority );
    	
    	this.priority = priority;
    	
    	// Bound priority to be between 1 and 100 (inclusive)
    	if ( this.priority > 100 ) this.priority = 100;
    	else if ( this.priority < 1 ) this.priority = 1;
    	
    	LOGGER.debug( "EXITING: " + CLASS_NAME + "." + methodName );
    }
    
    
    /**
     * Retrieve the priority of this validation service.
     * 
     * @return priority of this service
     */
    public int getPriority()
    {
    	return this.priority;
    }

    
    
    /**
     * The Listener class which catches xsl:messages during the
     * transformation/stages of the Schematron schema.
     */
    private class Listener implements javax.xml.transform.ErrorListener
    {
      public void warning( TransformerException e ) 
      {
        warnings.add( e.getMessage() );
      }

      public void error( TransformerException e )
        throws TransformerException
      {
        throw e;
      }

      public void fatalError( TransformerException e )
        throws TransformerException
      {
        throw e;
      }
      
    }



	@Override
	public void validate(Metacard metacard) throws ValidationException {
    	//TODO Refactor this method
		LOGGER.debug( "Using .sch ruleset: " + this.schematronSchemaFilename );
    	
    	// Convert the metacard's metadata to a String
		String metadata = metacard.getMetadata();
		LOGGER.debug("metadata: " + metadata);
		
		// Create a Reader for the catalog entry's contents
		StringReader metadataReader = new StringReader( metadata );

		try 
		{
			// Using the precompiled/stored Schematron validator, validate the catalog entry's contents
			Transformer transformer = validator.newTransformer();
			DOMResult schematronResult = new DOMResult();
			transformer.transform( new StreamSource( metadataReader ), schematronResult );
			this.report = new SvrlReport( schematronResult );

			LOGGER.trace( "SVRL Report:\n\n" + report.getReportAsText() );
			
			// If the Schematron validation failed, then throw an exception with details of the errors
			// and warnings from the Schematron report included in the exception that is thrown to the client.
			if ( !this.report.isValid( this.suppressWarnings ) )
			{
				List<String> warnings = new ArrayList<String>();
				
				StringBuffer errorMessage =  new StringBuffer( "Schematron validation failed.\n\n" );
				List<String> errors = this.report.getErrors();
				
				LOGGER.debug( "errors.size() = " + errors.size() );
				for ( String error : errors )
				{
					errorMessage.append( error );
					errorMessage.append( "\n" );
				}
				// If warnings are to be included from the Schematron report as part of the errors message
				if ( !this.suppressWarnings )
				{
					warnings = this.report.getWarnings();
					LOGGER.debug( "warnings.size() = " + warnings.size() );
					for ( String warning : warnings )
					{
						LOGGER.debug( "warning = " + warning );
						errorMessage.append( warning );
						errorMessage.append( "\n" );
					}
				}
				LOGGER.debug(errorMessage.toString());
				throw new SchematronValidationException(errorMessage.toString(), errors, warnings);
			}

		} 
		catch ( TransformerException te ) 
		{
			LOGGER.warn( "Could not setup validator to perform validation", te);			
		    throw new SchematronValidationException ( "Could not setup validator to perform validation.");
		}
    }

    
}
