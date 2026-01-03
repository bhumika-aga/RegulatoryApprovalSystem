package com.enterprise.regulatory.config;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.DeploymentBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class CamundaDeploymentConfig {

    private final RepositoryService repositoryService;

    @EventListener(ApplicationReadyEvent.class)
    public void deployFormsWithProcess() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

            DeploymentBuilder deployment = repositoryService.createDeployment()
                    .name("regulatory-approval-forms")
                    .enableDuplicateFiltering(true);

            // Deploy all .form files from bpmn folder
            Resource[] formResources = resolver.getResources("classpath:bpmn/*.form");
            int formCount = 0;
            for (Resource resource : formResources) {
                String filename = resource.getFilename();
                if (filename != null) {
                    deployment.addInputStream(filename, resource.getInputStream());
                    log.info("Adding form to deployment: {}", filename);
                    formCount++;
                }
            }

            if (formCount > 0) {
                deployment.deploy();
                log.info("Successfully deployed {} Camunda Forms", formCount);
            } else {
                log.warn("No .form files found in classpath:bpmn/");
            }

        } catch (Exception e) {
            log.error("Failed to deploy Camunda Forms", e);
        }
    }
}
