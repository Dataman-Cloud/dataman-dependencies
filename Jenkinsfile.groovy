def projectName = "dependencies"
def gitRepo = "git@github.com:Dataman-Cloud/dataman-dependencies.git"
node("build") {
    stage("Common") {
        echo "Environment: ${ENV}, Version: ${VERSION}"
        //前置检查
        //1. develop分支不需带上版本号, master分支需要带上版本号
        if (params.BRANCH != "origin/master") {
            if (params.VERSION != "") {
                error("Only [master] branch can have version. Please check your input!")
            }
        } else {
            if (params.VERSION == "") {
                error("[master] branch should have version. Please check your input!")
            }
        }

        //2. 环境检查
        if (params.ENV != "test" && params.ENV != "release") {
            error("[Environment] should be test, release")
        }

        //3. 发布master到生产之前进行二次确认
        if (params.ENV == "release") {
            try {
                timeout(time: 15, unit: 'SECONDS') {
                    input message: '将会直接直接发布Release, 确定要发布吗',
                            parameters: [[$class      : 'BooleanParameterDefinition',
                                          defaultValue: false,
                                          description : '点击将会发布Release',
                                          name        : '发布Release']]
                }
            } catch (err) {
                def user = err.getCauses()[0].getUser()
                error "Aborted by:\n ${user}"
            }
        }
    }
}
try {
//1. 使用构建Node进行构建
    if (params.ENV != "release") {
        node("build") {
            stage("Git-pull") {
                // 1. 从Git中clone代码
                git branch: "master", url: "${gitRepo}"
            }

            stage("Test-Deploy-Java") {
                // 2. 运行Maven构建
                sh "mvn clean install"
                sh "mvn clean deploy"
            }
        }
    }
} catch (Exception ex) {
    error 'build test fail ' + ex
}

//5. 发布到生产(prod)环境: 只有打包master分支, 才进行prod环境部署
if (params.ENV == "release" && params.BRANCH == "origin/master") {
    node("build") {
        def tagVersion = "${projectName}-V${VERSION}"
        stage("Prepare") {
            git branch: "master", url: "${gitRepo}"
        }
        stage("Maven-deploy") {
            sh "mvn versions:set -DnewVersion=${VERSION}"
            sh "mvn clean install"
            sh "mvn clean deploy"
        }
        //推送到公有仓库
        //公有仓库不能有源码包,公有harbor仓库命名有要求
        stage("public:Maven-Install") {
            sh "echo started deploy public registry"
            sh "mvn clean install -Dmaven.test.skip=true -Dmaven.source.skip=true -Ppublic"
        }

        stage("public:Maven-deploy") {
            sh "mvn clean deploy -Dmaven.test.skip=true -Dmaven.source.skip=true -Ppublic"
        }

        stage("Cleanup") {
            //5. 打tag
            sh "git tag ${tagVersion} -m 'Release ${tagVersion}'"
            sh "git push origin master"
            sh "git push origin ${tagVersion}"

            //6. 恢复重置
            sh "echo 'Reset the version to master-SNAPSHOT'"
            sh "git reset --hard"
        }
    }
}