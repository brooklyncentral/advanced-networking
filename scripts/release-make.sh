#!/usr/bin/env bash
# 
# Not using maven release plugin as:
#  * not all projects are tagged
#  * incubator-brooklyn is using a different scm for the tags
#  * version is changed in more than the pom.xml files

# CUSTOMIZE:

: ${RELEASE_DATE:=${1:-$(date +"%Y%m%d.%H%M")}}

: ${MAVEN_REPO_ID:=cloudsoft-artifactory-repo}
: ${MAVEN_REPO_URL:=http://ccweb.cloudsoftcorp.com/maven/libs-release-local/}

# Note advanced networking does not have a separate version identifier in code, but it should!
# BROOKLYN_VERSION_BELOW
: ${BROOKLYN_GA_VERSION:=0.8.0}
# ADVANCED_NETWORKING_VERSION_BELOW
: ${ADVANCED_NETWORKING_GA_VERSION:=0.8.0}
: ${BROOKLYN_STABLE_VERSION:=0.8.0-???}


# /CUSTOMIZE


#stop script on error
set -e

if [ ! -d .git ]; then
  echo "Must be run from the advanced-networking root folder"
  exit 1
fi

echo Deploying to ${MAVEN_REPO_URL} tagged ${RELEASE_DATE}.
echo Building from:
echo incubator-brooklyn: $BROOKLYN_STABLE_VERSION
echo advanced-networking: $(git symbolic-ref HEAD 2> /dev/null || git rev-parse HEAD) @ $(git config --get remote.origin.url)
echo

# Needed for Java 6
export MAVEN_OPTS="-Xmx1024m -XX:MaxPermSize=256m"

ADVANCED_NETWORKING_PATH=`pwd`
RELEASES_PATH=${ADVANCED_NETWORKING_PATH}/ignored/releases
MAVEN_REPO_PATH=${ADVANCED_NETWORKING_PATH}/ignored/maven-repo
CV=${ADVANCED_NETWORKING_PATH}/scripts/change-version.sh
REVISIONS=${ADVANCED_NETWORKING_PATH}/vcloud-director-nat-microservice/src/main/assembly/conf/revisions.txt

function check_no_snapshot_deps {
    local SNAPSHOTS=`mvn --batch-mode dependency:list -DexcludeTransitive=true | \
               grep '\[INFO\]    .*SNAPSHOT' | \
               sed -e 's/^.\{6\}//' | \
               sort | \
               uniq`
               
    if [ ! -z "$SNAPSHOTS" ]; then
    	echo
        echo FATAL: $REPO_NAME has SNAPSHOT dependencies, aborting:
        echo "$SNAPSHOTS"
        exit 1
    fi
}

function change_version {
    local REPO_NAME=`basename $(pwd)`

    echo
    echo Changing version of ${REPO_NAME} at `date`
    echo =================================================================

    ${CV} ${BROOKLYN_GA_VERSION}-SNAPSHOT $BROOKLYN_STABLE_VERSION
    ${CV} ADVANCED_NETWORKING ${ADVANCED_NETWORKING_GA_VERSION}-SNAPSHOT ${ADVANCED_NETWORKING_GA_VERSION}-${RELEASE_DATE}

    find . -name "*.bak" -delete
}

function build_and_deploy {
    local REPO_PATH=$1
    local BUILD_ARGS=$2

    pushd ${REPO_PATH} > /dev/null

    local REPO_REV=`git rev-parse --verify HEAD`
    local REPO_NAME=`basename $(pwd)`
    local REPO_BRANCH=`git branch --no-color 2> /dev/null | sed -e '/^[^*]/d' -e 's/* \(.*\)/\1/'`
    echo ${REPO_NAME}: ${REPO_BRANCH}: ${REPO_REV} >> ${REVISIONS}
    
    echo
    echo Building ${REPO_NAME} at `date`
    echo =================================================================
    
    change_version
    check_no_snapshot_deps

    mvn --batch-mode clean deploy -Dmaven.repo.local=${MAVEN_REPO_PATH} -Dgpg.skip=true \
    	-DaltDeploymentRepository=${MAVEN_REPO_ID}::default::${MAVEN_REPO_URL} \
        -DskipTests \
    	${BUILD_ARGS}

    popd > /dev/null
}

mkdir -p ignored
mkdir -p ${MAVEN_REPO_PATH}
rm -f ${REVISIONS}

build_and_deploy ./
echo incubator-brookyn: $BROOKLYN_STABLE_VERSION >> ${REVISIONS}
REVISIONS_SUMMARY=`cat ${REVISIONS}`

echo
echo Tag projects at `date`
echo =================================================================
echo "${REVISIONS_SUMMARY}"

## advanced-networking
git remote -v | grep releases || \
    git remote add releases git@github.com:cloudsoft/advanced-networking-cloudsoft-releases.git
git add --all
git commit -m "Release version ${ADVANCED_NETWORKING_GA_VERSION}-${RELEASE_DATE}

${REVISIONS_SUMMARY}"

git tag nightly-${RELEASE_DATE}
git push releases nightly-${RELEASE_DATE}

echo Successfully created release ${RELEASE_DATE}
