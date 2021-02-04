package com.taveeshsharma.requesthandler.orchestration;

import com.taveeshsharma.requesthandler.dto.documents.Job;
import com.taveeshsharma.requesthandler.manager.DatabaseManager;
import com.taveeshsharma.requesthandler.orchestration.algorithms.SchedulingAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class JobTracker {

    private static final Logger logger = LoggerFactory.getLogger(JobTracker.class);

    @Autowired
    DatabaseManager dbManager;

    @Autowired
    SchedulingAlgorithm roundRobinAlgorithm;

    @Scheduled(fixedRate = 2*60*1000, initialDelay = 60*1000)
    public void track(){
        Measurement.acquireWriteLock();
        logger.info("Job tracking is being perfomed");
        List<Job> activeJobs= Measurement.getJobs();
        //TODO synchronize appropriately as well as how often does the thread check
        //if end time is reached remove job
        //if its a recurring job once
        //loop backwards so as to avoid skipping an index if I remove an element
        Date currentTime = new Date();
        for(int i=activeJobs.size()-1;i>=0;i--){
            Job job=activeJobs.get(i);
            if(job.isRemovable()){
                activeJobs.remove(i);
                logger.info("Job id with "+job.getKey() +" removed");
            }
            else if(job.isResettable(currentTime)){
                job.reset();
                dbManager.upsertJob(job);
                logger.info("Job id with "+job.getKey() +" is reset");
            }
        }
        logger.info("Current Job Size is " + activeJobs.size());
        Measurement.releaseWriteLock();
        logger.info("Job Tracker has Finished");
    }
}
