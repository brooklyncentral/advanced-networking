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

import brooklyn.networking.vclouddirector.natservice.domain.NatRuleSummary;
import brooklyn.rest.apidoc.Apidoc;

import com.wordnik.swagger.core.ApiError;
import com.wordnik.swagger.core.ApiErrors;
import com.wordnik.swagger.core.ApiOperation;
import com.wordnik.swagger.core.ApiParam;

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

            @ApiParam(name = "identity", value = "User identity", required = true)
            @QueryParam("identity") String identity,

            @ApiParam(name = "credential", value = "User credential", required = true)
            @QueryParam("credential") String credential
            ) ;

    @PUT
    @ApiOperation(value = "Opens port forwarding",
            notes="Returns the return value (status 200) on success")
    @ApiErrors(value = {
            @ApiError(code = 404, reason = "Could not find application, entity or effector")
            })
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED})
    public Response openPortForwarding(
            @ApiParam(name = "endpoint", value = "Cloud endpoint URL", required = true)
            @QueryParam("endpoint") String endpoint,

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
