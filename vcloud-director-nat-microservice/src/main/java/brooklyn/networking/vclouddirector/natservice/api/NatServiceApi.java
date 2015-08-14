package brooklyn.networking.vclouddirector.natservice.api;

import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.wordnik.swagger.core.ApiError;
import com.wordnik.swagger.core.ApiErrors;
import com.wordnik.swagger.core.ApiOperation;
import com.wordnik.swagger.core.ApiParam;

import org.apache.brooklyn.rest.apidoc.Apidoc;

import brooklyn.networking.vclouddirector.natservice.domain.NatRuleSummary;

/**
 * REST api for accessing/updating NAT rules on vCloud Director's Edge Gateway.
 * 
 * The Edge Gateway does not handle concurrent access (because an update requires
 * a chain of calls to: 1) read existing; 2) create list of existing+new; 3) write everything.
 * 
 * Therefore this service provides controlled access to ensure updates from multiple callers
 * are done safely and sequentially.
 * 
 * The {@code identity} can be the {@code <user>@<vOrg>}, or alternatively just the {@code <user>}
 * if the vOrg is included in the endpoint URL.
 */
@Path("/v1/nat")
@Apidoc("Nat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface NatServiceApi {

    @GET
    @ApiOperation(value = "Fetch all NAT Rules",
            responseClass = "brooklyn.rest.domain.NatRuleSummary",
            multiValueResponse = true)
    @ApiErrors(value = {
            @ApiError(code = 404, reason = "Could not find application or entity")
            })
    public List<NatRuleSummary> list(
            @ApiParam(name = "endpoint", value = "Cloud endpoint URL", required = true)
            @QueryParam("endpoint") String endpoint,

            @ApiParam(name = "vdc", value = "vDC name", required = false)
            @QueryParam("vdc") String vDC,

            @ApiParam(name = "identity", value = "User identity", required = true)
            @QueryParam("identity") String identity,

            @ApiParam(name = "credential", value = "User credential", required = true)
            @QueryParam("credential") String credential
            ) ;

    @PUT
    @ApiOperation(value = "Opens port forwarding",
            notes="Returns the public ip:port (status 200) on success")
    @ApiErrors(value = {
            @ApiError(code = 404, reason = "Could not find ???") // TODO
            })
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED})
    public String openPortForwarding(
            @ApiParam(name = "endpoint", value = "Cloud endpoint URL", required = true)
            @QueryParam("endpoint") String endpoint,

            @ApiParam(name = "vdc", value = "vDC name", required = false)
            @QueryParam("vdc") String vDC,

            @ApiParam(name = "identity", value = "User identity", required = true)
            @QueryParam("identity") String identity,

            @ApiParam(name = "credential", value = "User credential", required = true)
            @QueryParam("credential") String credential,

            @ApiParam(name = "protocol", value = "Protocol", required = true)
            @QueryParam("protocol") String protocoll,

            @ApiParam(name = "original", value = "Original (i.e. public-side) ip or ip:port", required = true)
            @QueryParam("original") String original,
            
            @ApiParam(name = "originalPortRange", value = "Original (i.e. public-side) port range (must not be set if 'original' includes port)")
            @QueryParam("originalPortRange") String originalPortRange,
            
            @ApiParam(name = "translated", value = "Translated (i.e. private-side) ip:port", required = true)
            @QueryParam("translated") String translated
            );
    
    @DELETE
    @ApiOperation(value = "Deletes the given port forwarding rule",
            notes="Returns the return value (status 200) on success")
    @ApiErrors(value = {
            @ApiError(code = 404, reason = "Could not find NAT Rule")
            })
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED})
    public Response closePortForwarding(
            @ApiParam(name = "endpoint", value = "Cloud endpoint URL", required = true)
            @QueryParam("endpoint") String endpoint,

            @ApiParam(name = "vdc", value = "vDC name", required = false)
            @QueryParam("vdc") String vDC,

            @ApiParam(name = "identity", value = "User identity", required = true)
            @QueryParam("identity") String identity,

            @ApiParam(name = "credential", value = "User credential", required = true)
            @QueryParam("credential") String credential,

            @ApiParam(name = "protocol", value = "Protocol", required = true)
            @QueryParam("protocol") String protocoll,

            @ApiParam(name = "original", value = "Original (i.e. public-side) ip:port", required = true)
            @QueryParam("original") String original,
            
            @ApiParam(name = "translated", value = "Translated (i.e. private-side) ip:port", required = true)
            @QueryParam("translated") String translated
            );
}
