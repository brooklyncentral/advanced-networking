DNAT Micro-service
===

The DNAT micro-service provides a single service for multiple Brooklyn servers to make
concurrent changes to the DNAT rules on a vcloud-director gateway. This will 
prevent a race condition that can occur as the vCD REST API requires that the full
list of DNAT rules be download, modified, then the complete list uploaded in order
to make changes to the rules.

The instructions below assume that the micro-service is running on the same server 
as Brooklyn, which may not be the case in production. If the micro-service and Brooklyn
are running on different servers, the endpoint configured in brooklyn.properties
should be changed to reflect the address of the micro-service instead of 'localhost'

NOTE: There should be one and only one DNAT micro-service per vOrg. If multiple
Brooklyn servers are targeting the same vOrg (such as in development / test) then all
Brooklyn servers should be using the same micro-service.


### Deployment and Configuration

To deploy the micro-service:

* Build the `vcloud-director-nat-microservice` project, which will create a
distributable .tar.gz file at `target/brooklyn-networking-vcloud-director-nat-microservice-dist.tar.gz`

* Unpack the tarball to a suitable location

* Create a file at `~/.brooklyn/dnat-microservice.properties` and set the endpoint,
trustStore, trustStorePassword and port range for each vCD named location that you have 
defined in your brooklyn.properties. 

  * The endpoint should be the URL used to define the location, but should *not*
    include `/api` at the end. Both trustStore and trustStorePassword should be blank.
    NOTE: No credentials are defined in the micro-service properties file; the
    credentials are passed to the micro-service REST API and are not stored by the
    micro-service.

  * The trustStore and trustStorePassword are optional.
  
  * The `portRange` in the properties file is optional - it gives the default for that
    endpoint, if a value is not passed in the REST API call. If absent, this defaults 
    to `1024+`.

  * E.g. if you have two named locations defined by
    'brooklyn.location.named.my-vorg-1=XXXXX' and 'brooklyn.location.named.my-vorg-2=XXXX'
    you would use the following:

```
my-vorg-1.endpoint=https://mycompany.vchs.vmware.com
my-vorg-1.trustStore=
my-vorg-1.trustStorePassword=
my-vorg-1.portRange=11000-18000

my-vorg-2.endpoint=https://vchs.mycompany.com
my-vorg-2.trustStore=
my-vorg-2.trustStorePassword=
my-vorg-2.portRange=12000+
```


### Launching

* To start the microservice, use the `start.sh` script.

  * Optionally `--publicPortRange <range>` can be passed as a command line argument,
    which will be the global default if not overridden in the properties file and
    not passed in the REST API call.
 
  * For example, from the folder that the tarball was extracted to:

```
nohup ./start.sh launch --endpointsProperties ~/.brooklyn/dnat-microservice.properties &
```


### Configuring Brooklyn

* To enable Brooklyn to use the service, add the following to your `brooklyn.properties`
  file (with your URL, obviously) and restart Brooklyn:

```
# Enable NAT micro-service
advancednetworking.vcloud.network.microservice.endpoint=https://localhost:8443
```


### Using the REST API

The REST api calls often include the following parameters:

* `endpoint`: the vCloud Director URL, without the suffix `/api`.
  e.g. https://emea01.canopy-cloud.com or https://emea01.canopy-cloud.com/cloud/org/cct-emea01/,
  where the latter includes the vOrg.

* `identity`: either the `<user>@<vOrg>`, or just the `<user>` (the latter only if vOrg is 
  included in the endpoint) 

* `credential`: the password
