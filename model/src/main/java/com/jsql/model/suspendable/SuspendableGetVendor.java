package com.jsql.model.suspendable;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.jsql.model.InjectionModel;
import com.jsql.model.bean.util.Header;
import com.jsql.model.bean.util.Interaction;
import com.jsql.model.bean.util.Request;
import com.jsql.model.exception.StoppedByUserSlidingException;
import com.jsql.model.injection.vendor.MediatorVendor;
import com.jsql.model.injection.vendor.model.Vendor;
import com.jsql.model.suspendable.callable.CallablePageSource;
import com.jsql.model.suspendable.callable.ThreadFactoryCallable;
import com.jsql.util.I18nUtil;

/**
 * Runnable class, define insertionCharacter that will be used by all futures requests,
 * i.e -1 in "[..].php?id=-1 union select[..]", sometimes it's -1, 0', 0, etc,
 * this class/function tries to find the working one by searching a special error message
 * in the source page.
 */
public class SuspendableGetVendor extends AbstractSuspendable<Vendor> {
    
    /**
     * Log4j logger sent to view.
     */
    private static final Logger LOGGER = Logger.getRootLogger();

    public SuspendableGetVendor(InjectionModel injectionModel) {
        super(injectionModel);
    }

    /**
     * 
     */
    @Override
    public Vendor run(Object... args) throws StoppedByUserSlidingException {
        
        Vendor vendor = null;
        
        if (this.injectionModel.getMediatorVendor().getVendorByUser() != this.injectionModel.getMediatorVendor().getAuto()) {
            
            vendor = this.injectionModel.getMediatorVendor().getVendorByUser();
            LOGGER.info(I18nUtil.valueByKey("LOG_DATABASE_TYPE_FORCED_BY_USER") +" ["+ vendor +"]");
            
        } else {
        
            // Concurrent search and let the user stops the process if needed.
            // SQL: force a wrong ORDER BY clause with an inexistent column, order by 1337,
            // and check if a correct error message is sent back by the server:
            //         Unknown column '1337' in 'order clause'
            // or   supplied argument is not a valid MySQL result resource
            
            ExecutorService taskExecutor;
            
            if (this.injectionModel.getMediatorUtils().getPreferencesUtil().isLimitingThreads()) {
                
                int countThreads = this.injectionModel.getMediatorUtils().getPreferencesUtil().countLimitingThreads();
                taskExecutor = Executors.newFixedThreadPool(countThreads, new ThreadFactoryCallable("CallableGetVendor"));
                
            } else {
                
                taskExecutor = Executors.newCachedThreadPool(new ThreadFactoryCallable("CallableGetVendor"));
            }
            
            CompletionService<CallablePageSource> taskCompletionService = new ExecutorCompletionService<>(taskExecutor);
            
            String insertionCharacter = "'\"#-)'\"";
            
            taskCompletionService.submit(
                new CallablePageSource(
                    insertionCharacter,
                    insertionCharacter,
                    this.injectionModel,
                    "get:vendor"
                )
            );

            if (this.isSuspended()) {
                throw new StoppedByUserSlidingException();
            }
            
            try {
                CallablePageSource currentCallable = taskCompletionService.take().get();

                String pageSource = currentCallable.getContent();
                
                MediatorVendor mediatorVendor = this.injectionModel.getMediatorVendor();
                Vendor[] vendors = mediatorVendor.getVendors().stream().filter(v -> v != mediatorVendor.getAuto()).toArray(Vendor[]::new);
                
                // Test each vendor
                for (Vendor vendorTest: vendors) {
                    
                  if (
                      pageSource.matches(
                          "(?si).*("
                          + vendorTest.instance().fingerprintErrorsAsRegex()
                          + ").*"
                      )
                  ) {
                      vendor = vendorTest;
                      LOGGER.debug("Found database ["+ vendor +"]");
                      break;
                  }
                }
            } catch (InterruptedException | ExecutionException e) {
                
                LOGGER.error("Interruption while determining the type of database", e);
                Thread.currentThread().interrupt();
            }
                
            // End the job
            try {
                taskExecutor.shutdown();
                
                if (!taskExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                    taskExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                
                LOGGER.error(e.getMessage(), e);
                Thread.currentThread().interrupt();
            }
            
            vendor = this.initializeVendor(vendor);
        }
        
        Request requestSetVendor = new Request();
        requestSetVendor.setMessage(Interaction.SET_VENDOR);
        requestSetVendor.setParameters(vendor);
        this.injectionModel.sendToViews(requestSetVendor);
        
        return vendor;
    }

    private Vendor initializeVendor(Vendor vendor) {
        
        Vendor vendorFixed = vendor;
        
        if (vendorFixed == null) {
            
            vendorFixed = this.injectionModel.getMediatorVendor().getMySQL();
            LOGGER.warn(I18nUtil.valueByKey("LOG_DATABASE_TYPE_NOT_FOUND") +" ["+ vendorFixed +"]");
            
        } else {
            
            LOGGER.info(I18nUtil.valueByKey("LOG_USING_DATABASE_TYPE") +" ["+ vendorFixed +"]");
            
            Map<Header, Object> msgHeader = new EnumMap<>(Header.class);
            msgHeader.put(
                Header.URL,
                this.injectionModel.getMediatorUtils().getConnectionUtil().getUrlByUser()
            );
            msgHeader.put(Header.VENDOR, vendorFixed);
            
            Request requestDatabaseIdentified = new Request();
            requestDatabaseIdentified.setMessage(Interaction.DATABASE_IDENTIFIED);
            requestDatabaseIdentified.setParameters(msgHeader);
            this.injectionModel.sendToViews(requestDatabaseIdentified);
        }
        
        return vendorFixed;
    }
}