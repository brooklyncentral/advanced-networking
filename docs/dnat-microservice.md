DNAT Micro-service
===

The DNAT micro-service provides a single service for multiple AMP servers to make
concurrent changes to the DNAT rules on a vcloud-director gateway. This will 
prevent a race condition that can occur as the vCD REST API requires that the full
list of DNAT rules be download, modified, then the complete list uploaded in order
to make changes to the rules.

The instructions below assume that the micro-service is running on the same server 
as AMP, which may not be the case in production. If the micro-service and AMP
are running on different servers, the endpoint configured in brooklyn.properties
should be changed to reflect the address of the micro-service instead of 'localhost'

NOTE: There should be one and only one DNAT micro-service per vOrg. If multiple
rAMP servers are targeting the same vOrg (such as in development / test) then all
rAMP servers should be using the same micro-service.

To deploy the micro-service:

* Build the `vcloud-director-nat-microservice` project, which will create a
distributable .tar.gz file at `target/brooklyn-networking-vcloud-director-nat-microservice-dist.tar.gz`

* Unpack the tarball to a suitable location

* Create a file at `~/.brooklyn/dnat-microservice.properties` and set the endpoint,
trustStore and trustStorePassword for each vCD named location that you have defined
in your brooklyn.properties. E.g. if you have two named locations defined by
'brooklyn.location.named.my-vorg-1=XXXXX' and 'brooklyn.location.named.my-vorg-2=XXXX'
you would use the following:

```
my-vorg-1.endpoint=https://mycompany.vchs.vmware.com
my-vorg-1.trustStore=
my-vorg-1.trustStorePassword=

my-vorg-2.endpoint=https://vchs.mycompany.com
my-vorg-2.trustStore=
my-vorg-2.trustStorePassword=
```


 * The endpoint should be the URL used to define the location, but should *not*
include `/api` at the end. Both trustStore and trustStorePassword should be blank.
NOTE: No credentials are defined in the micro-service properties file; the
credentials are passed to the micro-service REST API and are not stored by the
micro-service.

 * To start the microservice, run the following from the folder that the tarball
was extracted to:

```
nohup ./start.sh launch --endpointsProperties ~/.brooklyn/dnat-microservice.properties &
```

 * To enable AMP to use the service, add the following to you `brooklyn.properties`
file and restart rAMP:

```
# Enable NAT micro-service
advancednetworking.vcloud.network.microservice.endpoint=https://localhost:8443
```
