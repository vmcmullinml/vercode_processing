def call(Map options) {
    String veracodeApiWrapperImage = 'veracode/api-wrapper-java:22.6.10.2.2'
    String veracodePipelineScanImage = 'veracode/pipeline-scan:cmd-22.5.0'
    String buildImage = options.buildImage
    String archivePath = options.archivePath ?: '/app/build/libs'
    String policyScanBranch = options.policyScanBranch ?: 'veracode-policy-scan'
    String applicationName = options.applicationName ?: 'unknown'
    String sandboxName = options.sandboxName ?: 'unknown'
    Boolean forcePolicyScan = options.forcePolicyScan ?: false
    String scanName = options.scanName ?: env.BUILD_TAG
    Boolean debug = options.debug ?: false

    if (!buildImage) {
        echo "[FATAL]: 'buildImage' option is required!"
        return
    }

    try {
        sh """
            docker run --rm -i ${buildImage} tar cf - ${archivePath} | tar xf -
            zip -9r "${WORKSPACE}/app.zip" .${archivePath}
        """
    } catch (Exception e) {
        echo "[FATAL]: zip file creation failed: " + e.toString()
        return
    }

    try {
        withCredentials([usernamePassword(credentialsId: 'veracode-api', passwordVariable: 'API_KEY_SECRET', usernameVariable: 'API_KEY_ID')]) {
            if (env.BRANCH_NAME == policyScanBranch || forcePolicyScan) {
                sh """
                    docker run -d -v ${WORKSPACE}:/work --entrypoint=sleep --name=veracode --workdir=/work docker.pennywise.cc/${veracodeApiWrapperImage} 1d
                    docker exec -t veracode java -jar /opt/veracode/api-wrapper.jar -action UploadAndScan \
                        -vid ${env.API_KEY_ID} -vkey ${env.API_KEY_SECRET} -appname ${applicationName} -createprofile false -criticality VeryHigh \
                        -sandboxname ${sandboxName} -createsandbox false -version jenkins-${env.JOB_NAME}-${env.BUILD_NUMBER} -autoscan true \
                        -maxretrycount 5 -debug -useragent "${veracodeApiWrapperImage}" -filepath /work/app.zip
                    docker rm -f veracode
                """
            } else {
                container('docker') {
                    sh """
                        docker run -d -v ${WORKSPACE}:/work --entrypoint sleep --name veracode docker.pennywise.cc/${veracodePipelineScanImage} 1d
                        docker exec -t veracode java -jar /opt/veracode/pipeline-scan.jar -vid "${env.API_KEY_ID}" -vkey "${env.API_KEY_SECRET}" -jf "scan-results.json" -fjf "scan-filtered-results.json" --file /work/app.zip || true
                        docker cp veracode:/home/luser/scan-results.json ${WORKSPACE}/scan-results.json
                        docker cp veracode:/home/luser/scan-filtered-results.json ${WORKSPACE}/scan-filtered-results.json
                        docker rm -f veracode
                    """
                }
                archiveArtifacts artifacts: '**/*-results.json', fingerprint: true
            }
        }
    } catch (Exception e) {
        echo "[FATAL]: Scan failed: " + e.toString()
    }
}
