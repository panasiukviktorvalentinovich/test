package com.smartbear.ccollab.rpc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.smartbear.ccollab.datamodel.client.ImageReturnType;
import com.smartbear.ccollab.warning.BinaryConversionWarnings;
import com.smartbear.ccollab.datamodel.ILargeObjectStore;
import com.smartbear.util.SmartBearUtils;
import org.apache.commons.io.IOUtils;

import com.smartbear.ccollab.CodeCollaborator;
import com.smartbear.ccollab.CollabReusable;
import com.smartbear.ccollab.binary.BinaryFileConversionException;
import com.smartbear.ccollab.binary.DocumentProvider;
import com.smartbear.ccollab.binary.ZoomScale;
import com.smartbear.ccollab.datamodel.Changelist;
import com.smartbear.ccollab.datamodel.Engine;
import com.smartbear.ccollab.datamodel.Product;
import com.smartbear.ccollab.datamodel.Review;
import com.smartbear.ccollab.datamodel.User;
import com.smartbear.ccollab.datamodel.Version;
import com.smartbear.util.NumberUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * A servlet for serving up pages of documents for review. Requests to this 
 * servlet must be authenticated and require three GET parameters:
 *   <dl>
 *   <dt><code>v</code></dt>
 *   <dd>The version id of the document to return.</dd>
 *   <dt><code>r</code></dt>
 *   <dd>The review of the document is in.</dd>
 *   <dt><code>p</code></dt>
 *   <dd>The page of the document to return.</dd>
 *   </dl>
 * <p>
 * If the user does not have permission to view the document, the request will
 * be answered with a 403 Forbidden status code. This would happen if the user
 * is authenticated, but is not a participant in the review <em>and</em> the
 * review has access restrictions enabled.
 * <p>
 * A 404 Not Found response will be returned if any of the following conditions
 * occur:
 * <ul>
 * <li>No review id was specified in the request.</li>
 * <li>The review id specified in the request does not map to a valid review.</li>
 * <li>No version id was specified in the request.</li>
 * <li>The version id specified in the request does not map to a valid version.</li>
 * <li>The requested version is not in the requested review.</li>
 * <li>The requested version is in the review, but is not a document.</li>
 * <li>The requested version is in the review and is a document, but the page
 * number requested is greater than the number of pages in the document.</li>
 * </ul>
 *  
 * @author Brandon
 */
public final class DocumentPageServlet extends HttpServlet
{
    private static final Log LOG = LogFactory.getLog(DocumentPageServlet.class);
    private static final int INVALID_DATAMODEL_OBJECT_ID = -1;

    private static final long serialVersionUID = 3177355797517616818L;

    private static final String PARAM_VERSION_ID = "v";
    private static final String PARAM_REVIEW_ID = "r";
    private static final String PARAM_PAGE = "p";
    private static final String PARAM_SCALE = "s";
    public static final String PARAM_TYPE = "t";

    public static final String CONTENT_TYPE_HEADER = "image/png";
    public static final String CONTENT_TYPE_BASE64_HEADER = "text/plain";
    
    @Override
    protected void doGet( HttpServletRequest request, HttpServletResponse response ) 
        throws ServletException, IOException
    {
        Engine engine = initializeEngine( request );
        if ( engine == null )
        {
        	// Could happen if the database is gone...
        	response.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
        	return;
        }
        
        final User user = requireAuthenticatedUser( response );
        if ( user == null )
            return;     // Error message already sent... we're done.
        
        if ( ! Product.getFeatures().supportsDocumentReview() )
        {
            //Send the product name to WebUI.
            String productNameInMessage = Product.current.getProductName();
            //Have to pass the information through Header, WebUI could read this through the header
            response.setHeader(SmartBearUtils.DOCUMENT_NOT_SUPPORTED_VERSION , productNameInMessage );
            //Currently Collab has not used this integer HttpServletResponse.SC_NOT_IMPLEMENTED
            //Make sure this is the only place we use it to distinguish this case
            response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED,"Document review is not supported in " + Product.current.getProductName() );


            return;
        }
        
        final Review review = loadRequestedReview( engine, request );
        if ( review == null )
        {
            response.sendError( HttpServletResponse.SC_NOT_FOUND, "No such review." );
            return;
        }
        
        // Check that the user has permissions to access the review.
        if ( ! requireReviewPermissions( review, user, response ) )
            return;     // Error message already sent... we're done.
        
        final Version version = loadRequestedVersion( engine, request );
        if ( version == null )
        {
            response.sendError( HttpServletResponse.SC_NOT_FOUND, "No such version." );
            return;
        }

        if (!version.isContentAvailable()) {
            response.sendError( HttpServletResponse.SC_NOT_FOUND, "Content is unavailable. This content may have been archived by the administrator." );
            return;
        }
        
        // Check that the version is in the review...
        if ( ! verifyVersionInReview( review, version ) )
        {
            response.sendError( HttpServletResponse.SC_NOT_FOUND, "Version is not in review." );
            return;
        }
        if(wasDocumentConvertedWithWarnings(version)){
            addWarningsToDocumentChat(review, version);
        }
        
        // Check the version is a document...
        ServletOutputStream outputStream = null;
        InputStream inputStream = null; 
        DocumentProvider documentProvider = CodeCollaborator.getInstance().getDocumentProvider();
        try
        {
            if ( ! documentProvider.isDocument( version ) )
            {
                response.sendError( HttpServletResponse.SC_NOT_FOUND, "Version is not a document." );
                return;
            }
            
            // Check that the requested page is in the document...
            int page = getRequestedPageNumber( request );
            if ( page < 1 )
            {
                response.sendError( HttpServletResponse.SC_NOT_FOUND, "Bad page number: " + page );
                return;
            }
            else if ( page > documentProvider.getPageCount( version ) )
            {
                response.sendError( HttpServletResponse.SC_NOT_FOUND, "Bad page number: " + page );
                return;
            }
            
            final ZoomScale scale;
            if ( documentProvider.isScalable( version ) )
            {
            	scale = getScale( request );
            }
            else
            {
            	scale = ZoomScale.ONEHUNDRED_PERCENT;
            }
            
            inputStream = documentProvider.getPageData( version, scale, page );
            if (inputStream == null) {
            	response.sendError(HttpServletResponse.SC_NOT_FOUND, "Content unavailable. This content may have been archived by the administrator.");
            	return;
            }            	
            
            setCacheHeaders( response );
            outputStream = response.getOutputStream();
            
            if (getImageReturnType(request) == ImageReturnType.BINARY) {
                // All the checks finally work out! Send the content!
                response.setContentType( CONTENT_TYPE_HEADER );
                IOUtils.copy( inputStream, outputStream );
            }

            if (getImageReturnType(request) == ImageReturnType.BASE64) {
                response.setContentType(CONTENT_TYPE_BASE64_HEADER);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    IOUtils.copy(inputStream, baos);

                    BufferedImage image;
                    String base64;
                    try (InputStream imageInputStream = new ByteArrayInputStream(baos.toByteArray())) {
                        image = ImageIO.read(imageInputStream);
                        base64 = Base64.getEncoder().encodeToString(baos.toByteArray());

                        String width = String.valueOf(image.getWidth());
                        String height = String.valueOf(image.getHeight());

                        outputStream.write((base64.concat(":").concat(width).concat(":").concat(height)).getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error converting file: " + e.getMessage());
                    }

            } 
        }
        catch ( BinaryFileConversionException bfce )
        {
            response.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error converting file: " + bfce.getMessage() );
        }
        finally
        {
            IOUtils.closeQuietly( inputStream );
            IOUtils.closeQuietly( outputStream );
        }
    }

    private ZoomScale getScale(HttpServletRequest request) 
    {
    	return ZoomScale.getByLabel( request.getParameter( PARAM_SCALE ) );
	}

    public static ImageReturnType getImageReturnType(HttpServletRequest request) {
        return ImageReturnType.fromStringValue(request.getParameter(PARAM_TYPE));
    }

	private void setCacheHeaders( HttpServletResponse response )		// NOPMD: unused parameter "response"
    {
        ServletUtils.setPrivateCache( response );
    }

    /**
     * Check if the review contains a changelist containing the specified version.
     * @param review the review 
     * @param version the version
     * @return true if the review contains a changelist with the specified version.
     */
    private boolean verifyVersionInReview( Review review, Version version )
    {
        Set<Version> allReviewVersions = Changelist.getAllVersionsWithPrevious(review.getChangelists());
        return allReviewVersions.contains( version );
    }

    /**
     * Get the page number contained in the request or -1 if there was not a 
     * page requested.
     * @param request the request object.
     * @return the page number requested.
     */
    private int getRequestedPageNumber( HttpServletRequest request )
    {
        return NumberUtils.parseInteger( request.getParameter( PARAM_PAGE ), -1 );
    }

    /**
     * Get the version that was requested in the request or <code>null</code> if
     * no version id was sent or the version id does not map to a valid version.
     * 
     * @param engine the engine for access to datamodel.
     * @param request the request.
     * @return the version that was requested, or <code>null</code> if no review
     * id was sent or the review does not map to a valid review.
     */
    private Version loadRequestedVersion( Engine engine, HttpServletRequest request )
    {
        int versionId = NumberUtils.parseInteger( request.getParameter( PARAM_VERSION_ID), INVALID_DATAMODEL_OBJECT_ID );
        return engine.loadEngineObjectById( Version.class, versionId );
    }

    /**
     * Get the review that was requested in the request or <code>null</code> if
     * no review id was sent or the review id does not map to a valid review.
     * 
     * @param engine the engine object for access to datamodel. 
     * @param request the request.
     * @return the review that was requested, or <code>null</code> if no review
     * id was sent or the review does not map to a valid review.
     */
    private Review loadRequestedReview( Engine engine, HttpServletRequest request )
    {
        int reviewId = NumberUtils.parseInteger( request.getParameter( PARAM_REVIEW_ID ), INVALID_DATAMODEL_OBJECT_ID );
        return engine.loadEngineObjectById( Review.class, reviewId );
    }

    /**
     * Initialize the engine for access to the database for processing the request.
     * 
     * @param request the HTTP request
     * @return the 
     */
    private Engine initializeEngine( HttpServletRequest request )
    {
        CollabReusable.init( request );
        return CollabReusable.getEngine( request );
    }

    /**
     * Require that the user is authenticated and return the authenticated user
     * if present and the account is not disabled. If the request was submitted
     * without a valid user account or the user account is currently disabled,
     * send an appropriate code and error message to the <code>response</code>.
     * <p>
     * If this method returns <code>null</code> <em>no further processing of 
     * the request should be performed</em>.
     *
     * @param response the HTTP response object
     * @return the user account for the active user or <code>null</code> if no
     * valid user was sent with the request.
     * 
     * @throws IOException if an attempt was made to write an error response to
     * <code>response</code> and it failed.
     */
    private User requireAuthenticatedUser( HttpServletResponse response )
        throws IOException
    {
        User user = CollabReusable.getCurrentUser();
        
        if ( user == null )
        {
            response.sendError( HttpServletResponse.SC_UNAUTHORIZED, "Must be logged in to access document data." );
            return null;
        }
        
        if ( ! user.isActive() )
        {
            response.sendError( HttpServletResponse.SC_UNAUTHORIZED, "User account is currently disabled." );
            user = null;
        }
        
        return user;
    }

    /**
     * Confirm that the user has permissions to view content in the review. If not,
     * send an appropriate HTTP error status to <code>response</code>. If this 
     * method returns false, the caller should immediately stop processing of
     * the request and return.
     * 
     * @param review the review.
     * @param user the user.
     * @param response the HTTP response object to send errors to.
     * @return true if the user has permission to access content in the review;
     * false otherwise.
     * 
     * @throws IOException if an error occurs sending a error response to <code>
     * response</code>.
     */
    private boolean requireReviewPermissions( Review review, User user, HttpServletResponse response )
        throws IOException
    {
        if ( review.canAccessContent( user ) )
            return true;

        // Don't give the user too much information about why, so as to discourage 
        // probing. Also, don't want to develop a reliance on a particular
        // permissioning scheme.
        response.sendError( HttpServletResponse.SC_FORBIDDEN );
        return false;
    }

    public boolean wasDocumentConvertedWithWarnings(Version version) throws IOException {
        ILargeObjectStore largeObjectStore = version.getEngine().getLargeObjectStore();
        return largeObjectStore.auxiliaryExists(version.getContentMD5(), BinaryConversionWarnings.WARNING_FILE_NAME);
    }

    private void addWarningsToDocumentChat(Review review, Version version) {
        ILargeObjectStore largeObjectStore = version.getEngine().getLargeObjectStore();

        try (ObjectInputStream objectIn = new ObjectInputStream(largeObjectStore.loadAuxiliary(
                version.getContentMD5(), BinaryConversionWarnings.WARNING_FILE_NAME))){

            BinaryConversionWarnings warnings = (BinaryConversionWarnings)objectIn.readObject();
            warnings.addToChat(review, version);

        } catch (Exception e) {
            LOG.error("Can't read warnings for version with contentMD5 " + version.getContentMD5(), e);
        }
    }

}
