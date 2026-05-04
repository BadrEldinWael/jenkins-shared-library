def call(Map config = [:]) {

    pipeline {
        agent any
         tools {
            jdk 'JDK-17'
            maven 'Maven-3.9'
        }
        stages {

            stage('Init Variables') {
                steps {
                    script {
                        env.IMAGE_NAME = config.imageName ?: "spring-petclinic"
                        env.AWS_REGION = config.region ?: "us-east-1"
                        env.ECR_REPO = config.ecrRepo
                        env.ACCOUNT_ID = config.accountId
                        env.TAG = "${env.BUILD_NUMBER}"   
                    }
                }
            }

            stage('Update Config (port=8081)') {
                steps {
                    sh '''
                    echo "server.port=8081" >> src/main/resources/application.properties
                    '''
                }
            }

            stage('Clean & Compile') {
                steps {
                    sh 'mvn clean compile'
                }
            }

            stage('Test') {
                steps {
                    sh 'mvn test'
                }
            }

          
          stage('Code Quality - SonarQube') {
    steps {
        withSonarQubeEnv('sonarqube-server') {

            withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {

                sh '''
                mvn sonar:sonar \
                -Dsonar.login=$SONAR_TOKEN \
                -Dsonar.host.url=http://localhost:9000 \
                -Dsonar.projectKey=spring-petclinic
                '''
            }
        }
    }
}

            stage('Package') {
                steps {
                    sh 'mvn package -DskipTests'
                }
            }

            stage('Docker Build') {
                steps {
                    sh 'docker build -t $IMAGE_NAME:$TAG .'
                }
            }

          
            stage('Security Scan') {
                steps {
                    sh '''
                    trivy image --exit-code 0 --severity HIGH,CRITICAL $IMAGE_NAME:$TAG
                    '''
                }
            }
            stage('Login to ECR') {
                steps {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'aws-creds'  
                    ]]) {
                        sh '''
                        aws ecr get-login-password --region $AWS_REGION | \
                        docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com
                        '''
                    }
                }
            }

            stage('Docker Tag & Push') {
                steps {
                    sh '''
                    docker tag $IMAGE_NAME:$TAG $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$TAG
                    docker push $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$TAG
                    '''
                }
            }

            stage('Run Container') {
                steps {
                    sh '''
                    docker stop petclinic || true
                    docker rm petclinic || true

                    docker run -d -p 8081:8081 --name petclinic \
                    $ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$TAG
                    '''
                }
            }
        }

        post {
            success {
                echo " Pipeline completed successfully - Image tag: ${env.TAG}"
            }
            failure {
                echo " Pipeline failed"
            }
        }
    }
}