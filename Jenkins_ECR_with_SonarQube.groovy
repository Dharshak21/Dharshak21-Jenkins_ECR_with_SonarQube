pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the GitHub URL')
        string(name: 'AWS_Account_Id', description: 'Enter the AWS Account Id')
        string(name: 'MailToRecipients', description: 'Enter the Mail Id for Approval')
        string(name: 'Docker_File_Name', defaultValue: 'Dockerfile', description: 'Enter the Name of Your Dockerfile (Eg: Dockerfile)')
        choice(
            choices: ["us-east-1","us-east-2","us-west-1","us-west-2","ap-south-1","ap-northeast-3","ap-northeast-2","ap-southeast-1","ap-southeast-2","ap-northeast-1","ca-central-1","eu-central-1","eu-west-1","eu-west-2","eu-west-3","eu-north-1","sa-east-1"],
            description: 'Select your Region Name (eg: us-east-1)',
            name: 'Region_Name'
        )
        string(name: 'ECR_Repo_Name', defaultValue: 'ecr_default_repo', description: 'ECR Repository (Default: ecr_default_repo)')
        string(name: 'Version_Number', defaultValue: '1.0', description: 'Enter the Version Number for ECR Image (Default: 1.0)')
        string(name: 'Workspace_name', defaultValue: 'Jenkins_ECR_with_SonarQube_CodeTest', description: 'Workspace name')
        string(name: 'AWS_Credentials_Id', defaultValue: 'AWS_Credentials', description: 'AWS Credentials Id')
        string(name: 'Git_Credentials_Id', defaultValue: 'Github_Credentials', description: 'Git Credentials Id')
        string(name: 'SONAR_PROJECT_NAME', defaultValue: 'SonarScannerCheck', description: 'Sonar Project Name (Default: SonarScannerCheck)')
    }
    
    environment {
        ECR_Credentials = "ecr:${params.Region_Name}:${params.AWS_Credentials_Id}"
        S3_Url = 'https://yamlclusterecs1.s3.amazonaws.com/master.yaml'
    }
    
    stages {
        stage('Clone the Git Repository') {
            steps {
                git branch: 'main', 
                credentialsId: "${params.Git_Credentials_Id}", 
                url: "${params.Git_Hub_URL}"
            }
        }
        
        stage('Docker start') {
            steps {
                sh '''
                docker ps || true
                docker start sonarqube || true
                docker start zaproxy || true
                curl -s ipinfo.io/ip > ip.txt || echo "Could not get IP" > ip.txt
                '''
            }
        }
        
        stage('Wait for SonarQube to Start') {
            steps {
                sleep(time: 120, unit: 'SECONDS') 
            }
        }
        
        stage('Send Sonar Analysis Report and Approval Email') {
            steps {
                script {
                    def Jenkins_IP = sh(script: 'cat ip.txt', returnStdout: true).trim()
                    emailext(
                        subject: "Approval Needed to Build Docker Image",
                        body: """
                            <p>SonarQube Analysis Report URL: http://${Jenkins_IP}:9000/dashboard?id=${params.SONAR_PROJECT_NAME}</p>
                            <p>Username: admin<br>Password: 12345</p>
                            <p>Please Approve to Build the Docker Image in Testing Environment</p>
                            <p><a href="${env.BUILD_URL}input/">Click to Approve</a></p>
                        """,
                        mimeType: 'text/html',
                        to: "${params.MailToRecipients}",
                        from: "dharshak214@gmail.com"
                    )
                }
            }
        }
        
        stage('Approval-Build Image') {
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: 'Please approve the build image process', ok: 'Proceed'
                }
            }
        }
        
        stage('Create ECR Repository') {
            steps {
                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding', 
                     credentialsId: "${params.AWS_Credentials_Id}", 
                     accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                     secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                ]) {
                    sh """
                    aws ecr create-repository --repository-name ${params.ECR_Repo_Name} --region ${params.Region_Name} || true
                    """
                }
            }
        }
        
        stage('Build and Push Docker Image') {
            steps {
                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding', 
                     credentialsId: "${params.AWS_Credentials_Id}", 
                     accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                     secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                ]) {
                    script {
                        // Find Dockerfile path
                        def dockerfilePath = sh(script: "find . -type f -name '${params.Docker_File_Name}' | head -n 1", returnStdout: true).trim()
                        
                        if (!dockerfilePath) {
                            error "Dockerfile not found in workspace!"
                        }
                        
                        echo "Found Dockerfile at: ${dockerfilePath}"
                        
                        def imageName = "${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com/${params.ECR_Repo_Name}:${params.Version_Number}"
                        
                        sh """
                            echo "Building Docker image: ${imageName}"
                            aws ecr get-login-password --region ${params.Region_Name} | docker login --username AWS --password-stdin ${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com
                            docker build -t ${imageName} -f ${dockerfilePath} .
                            docker push ${imageName}
                        """
                    }
                }
            }
        }
    }
    
    post {
        always {
            emailext(
                subject: "Jenkins Build: ${currentBuild.currentResult} - ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                body: """
                    <p><strong>Build Result:</strong> ${currentBuild.currentResult}</p>
                    <p><strong>Job:</strong> ${env.JOB_NAME}</p>
                    <p><strong>Build Number:</strong> ${env.BUILD_NUMBER}</p>
                    <p><strong>Build URL:</strong> <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                """,
                mimeType: 'text/html',
                to: "${params.MailToRecipients}",
                from: "dharshak214@gmail.com"
            )
        }
    }
}
