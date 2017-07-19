def generateTestBranches(function_test){
    def test_branches = [:]
    node{
        deleteDir()
        checkout scm
        def shareMethod = load("jobs/ShareMethod.groovy")
        def ALL_TESTS = function_test.getAllTests()
        def used_resources = function_test.getUsedResources()
        def TESTS = "${env.TESTS}"
        def test_stack = "-stack docker_local_run"
        List tests_group = Arrays.asList(TESTS.split(','))
        for(int i=0; i<tests_group.size(); i++){
            def test_name = tests_group[i]
            def label_name=ALL_TESTS[test_name]["label"]
            def test_group = ALL_TESTS[test_name]["TEST_GROUP"]
            def run_fit_test = ALL_TESTS[test_name]["RUN_FIT_TEST"]
            def run_cit_test = ALL_TESTS[test_name]["RUN_CIT_TEST"]
            def extra_hw = ALL_TESTS[test_name]["EXTRA_HW"]
            test_branches["manifest $test_name"] = {
                String node_name = ""
                try{
                    lock(label:label_name,quantity:1){
                        // Occupy an avaliable resource which contains the label
                        node_name = shareMethod.occupyAvailableLockedResource(label_name, used_resources)
                        node(node_name){
                            withEnv([
                                "SKIP_PREP_DEP=false",
                                "USE_VCOMPUTE=${env.USE_VCOMPUTE}",
                                "HTTP_STATIC_FILES=${env.HTTP_STATIC_FILES}",
                                "TFTP_STATIC_FILES=${env.TFTP_STATIC_FILES}",
                                "stash_manifest_name=${env.stash_manifest_name}",
                                "stash_manifest_path=${env.stash_manifest_path}",
                                "TEST_TYPE=manifest"])
                            {
                                withCredentials([
                                    usernamePassword(credentialsId: 'ESXI_CREDS',
                                                     passwordVariable: 'ESXI_PASS',
                                                     usernameVariable: 'ESXI_USER'),
                                    usernamePassword(credentialsId: 'ff7ab8d2-e678-41ef-a46b-dd0e780030e1',
                                                         passwordVariable: 'SUDO_PASSWORD',
                                                         usernameVariable: 'SUDO_USER'),
                                    string(credentialsId: 'INTERNAL_HTTP_ZIP_FILE_URL', variable: 'INTERNAL_HTTP_ZIP_FILE_URL'),
                                    string(credentialsId: 'INTERNAL_TFTP_ZIP_FILE_URL', variable: 'INTERNAL_TFTP_ZIP_FILE_URL')])
                                {
                                deleteDir()
                                dir("build-config"){
                                    checkout scm
                                }
                                env.BUILD_CONFIG_DIR = "build-config"
                                // Get the manifest file
                                unstash "$stash_manifest_name"
                                env.MANIFEST_FILE="$stash_manifest_path"
                                //println env.MANIFEST_FILE
                                // If the manifest file contains PR of on-http and RackHD,
                                // set the environment variable MODIFY_API_PACKAGE as true
                                // The test.sh script will install api package according to API_PACKAGE_LIST
                                sh '''#!/bin/bash
                                ./build-config/build-release-tools/HWIMO-BUILD ./build-config/build-release-tools/application/parse_manifest.py \
                                --manifest-file $MANIFEST_FILE \
                                --parameters-file downstream_file
                                '''
                                env.MODIFY_API_PACKAGE = false
                                if(fileExists ('downstream_file')) {
                                    sh '''
                                    echo "11111111111111111111Executed to fileExists('downstream_file')111111111"
                                    '''
                                    def props = readProperties file: 'downstream_file'
                                    sh "echo \"&&&&&&&The props are:\", ${props}"
                                    if(props['REPOS_UNDER_TEST']) {
                                        env.REPOS_UNDER_TEST = "${props.REPOS_UNDER_TEST}"
                                        def repos = env.REPOS_UNDER_TEST.tokenize(',')
                                        sh "echo \"2222222222222222222executed here to props['REPOS_UNDER_TEST']222222222222222222\", ${env.REPOS_UNDER_TEST}"
                                        sh "echo \"Executed here the line after 22222222\""
                                        if(repos.contains("on-http") && repos.contains("RackHD")){
                                            sh "echo \"2 Executed here the line after 22222222\""
                                            env.MODIFY_API_PACKAGE = true
                                        }
                                    }
                                }

                                sh "echo \"3 Executed here the line after 22222222\""
                                //sh './build-config/jobs/FunctionTest/prepare_common.sh'

                                sh "echo \"4 Executed here the line after 22222222\""
                                 
                                retry(3){
                                    // This scipts can be separated into manifest_src_prepare and common_prepare
                                    sh './build-config/jobs/FunctionTest/prepare_manifest.sh'
                                }
                                step ([$class: 'CopyArtifact',
                                projectName: 'Docker_Image_Build',
                                target: "$WORKSPACE"]);
    
                                function_test.functionTest(test_name,test_group, run_fit_test, run_cit_test, test_stack, extra_hw)
                                }
                            }
                        }
                    }
                } finally{
                    used_resources.remove(node_name)
                }
            }
        }
    }
    return test_branches
}

def runTests(function_test){
    def test_branches = generateTestBranches(function_test)
    if(test_branches.size() > 0){
        try{
            parallel test_branches
        } finally{
            archiveArtifacts(function_test)
        }
    }
}

def archiveArtifacts(function_test){
    def TESTS = "${env.TESTS}"
    function_test.archiveArtifactsToTarget("FunctionTest", TESTS)
}

return this

