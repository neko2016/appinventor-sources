// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2012 MIT, All rights reserved
// Released under the MIT License https://raw.github.com/mit-cml/app-inventor/master/mitlicense.txt

package com.google.appinventor.server.storage;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreInputStream;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.appengine.api.files.AppEngineFile;
import com.google.appengine.api.files.FileService;
import com.google.appengine.api.files.FileServiceFactory;
import com.google.appengine.api.files.FileWriteChannel;
import com.google.appinventor.server.CrashReport;
import com.google.appinventor.server.FileExporter;
import com.google.appinventor.server.flags.Flag;
import com.google.appinventor.server.storage.StoredData.FileData;
import com.google.appinventor.server.storage.StoredData.MotdData;
import com.google.appinventor.server.storage.StoredData.ProjectData;
import com.google.appinventor.server.storage.StoredData.UserData;
import com.google.appinventor.server.storage.StoredData.UserFileData;
import com.google.appinventor.server.storage.StoredData.UserProjectData;
import com.google.appinventor.server.storage.StoredData.RendezvousData;
import com.google.appinventor.server.storage.StoredData.WhiteListData;

import com.google.appinventor.server.storage.GalleryAppData;
import com.google.appinventor.server.storage.GalleryCommentData;

import com.google.appinventor.shared.rpc.Motd;
import com.google.appinventor.shared.rpc.project.Project;
import com.google.appinventor.shared.rpc.project.ProjectSourceZip;
import com.google.appinventor.shared.rpc.project.RawFile;
import com.google.appinventor.shared.rpc.project.TextFile;
import com.google.appinventor.shared.rpc.user.User;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

import com.google.appinventor.shared.rpc.project.GalleryApp;
import com.google.appinventor.shared.rpc.project.GalleryComment;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.Query;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Date;

import javax.annotation.Nullable;

/**
 * Implements the GalleryStorageIo interface using Objectify as the underlying data
 * store. This class provides the db support for gallery data, and is modeled after
 * StorageIo which handles the rest of the AI database.
 *
 * @author wolberd@gmail.com (David Wolber)
 *
 */
public class ObjectifyGalleryStorageIo implements  GalleryStorageIo {
  static final Flag<Boolean> requireTos = Flag.createFlag("require.tos", false);

  private static final Logger LOG = Logger.getLogger(ObjectifyStorageIo.class.getName());

  private static final String DEFAULT_ENCODING = "UTF-8";

  // TODO(user): need a way to modify this. Also, what is really a good value?
  private static final int MAX_JOB_RETRIES = 10;

  // Use this class to define the work of a job that can be retried. The
  // "datastore" argument to run() is the Objectify object for this job
  // (created with ObjectifyService.beginTransaction()). Note that all operations
  // on "datastore" should be for objects in the same entity group.
  @VisibleForTesting
  abstract class JobRetryHelper {
    public abstract void run(Objectify datastore) throws ObjectifyException;
    /*
     * Called before retrying the job. Note that the underlying datastore
     * still has the transaction active, so restrictions about operations
     * over multiple entity groups still apply.
     */
    public void onNonFatalError() {
      // Default is to do nothing
    }
  }

  // Create a final object of this class to hold a modifiable result value that
  // can be used in a method of an inner class.
  private class Result<T> {
    T t;
  }

  private FileService fileService;

  static {
    // Register the data object classes stored in the database
    ObjectifyService.register(GalleryAppData.class);
    ObjectifyService.register(GalleryCommentData.class);
  }

  ObjectifyGalleryStorageIo() {
    fileService = FileServiceFactory.getFileService();
  }

  // for testing
  ObjectifyGalleryStorageIo(FileService fileService) {
    this.fileService = fileService;

  }
  // we'll need to talk to the StorageIo to get developer names, so...
  private final transient StorageIo storageIo = 
      StorageIoInstanceHolder.INSTANCE;
  
  /**
   * create a new gallery app in database. This doesn't deal with aia or image file
   * which is put in gcs instead
   * @return id of the gallery app
   * 
   */
  @Override
  public GalleryApp createGalleryApp(final String title, final String projectName, final String description, final long projectId, final String userId) {

    final Result<GalleryAppData> galleryAppData = new Result<GalleryAppData>();
    try {
      // first job is on the gallery entity, creating the GalleryAppData object
      // and the associated files.
      runJobWithRetries(new JobRetryHelper() {
        @Override
        public void run(Objectify datastore) throws ObjectifyException {
          long date = System.currentTimeMillis();
          GalleryAppData appData = new GalleryAppData();
          appData.id = null;  // let Objectify auto-generate the project id
          
          appData.dateCreated = date;
          appData.dateModified = date;
          appData.title = title;
          appData.projectName= projectName;
          appData.description = description;
          appData.projectId=projectId;
          appData.userId=userId;
          datastore.put(appData); // put the appData in the db so that it gets assigned an id

          assert appData.id != null;
          galleryAppData.t = appData;
          // remember id in some way, as in below?
          // projectId.t = pd.id;
          // After the job commits projectId.t should end up with the last value
          // we've gotten for pd.id (i.e. the one that committed if there
          // was no error).
          // Note that while we cannot expect to read back a value that we've
          // written in this job, reading the assigned id from pd should work.

          Key<GalleryAppData> galleryKey = galleryAppKey(appData.id);
          
        }
 
      });

      
    } catch (ObjectifyException e) {
      
      throw CrashReport.createAndLogError(LOG, null,
          "gallery error", e);
    } 
    GalleryApp gApp = new GalleryApp();
    makeGalleryApp(galleryAppData.t, gApp);
    return gApp;
  }

  /**
   * Returns an array of recently published GalleryApps
   *
   * @return  list of gallery apps
   */
  @Override
  public List<GalleryApp> getRecentGalleryApps(int start, final int count) {
    final List<GalleryApp> apps = new ArrayList<GalleryApp>();
    // if i try to run this in runjobwithretries it tells me can't run
    // non-ancestor query as a transaction. ObjectifyStorageio has some samples
    // of not using transactions (run with) so i grabbed
    
    Objectify datastore = ObjectifyService.begin();
    for (GalleryAppData appData:datastore.query(GalleryAppData.class).order("-dateModified").offset(start).limit(count)) {
      
      GalleryApp gApp = new GalleryApp();
      makeGalleryApp(appData, gApp);
      apps.add(gApp);
    }
    return apps;
  }
  /**
   * Returns an array of most downloaded GalleryApps
   *
   * @return  list of gallery apps
   */
  @Override
  public List<GalleryApp> getMostDownloadedApps(int start, final int count) {
    final List<GalleryApp> apps = new ArrayList<GalleryApp>();
    // if i try to run this in runjobwithretries it tells me can't run
    // non-ancestor query as a transaction. ObjectifyStorageio has some samples
    // of not using transactions (run with) so i grabbed
    
    Objectify datastore = ObjectifyService.begin();
    for (GalleryAppData appData:datastore.query(GalleryAppData.class).order("-numDownloads").offset(start).limit(count)) {
      
      GalleryApp gApp = new GalleryApp();
      makeGalleryApp(appData, gApp);
      apps.add(gApp);
    }
    return apps;
  }
  
   /**
   * Returns an array of apps by a particular user
   *
   * @return  list of gallery apps
   */
  @Override
  public List<GalleryApp> getDeveloperApps(String userId, int start, final int count) {
    final List<GalleryApp> apps = new ArrayList<GalleryApp>();
    // if i try to run this in runjobwithretries it tells me can't run
    // non-ancestor query as a transaction. ObjectifyStorageio has some samples
    // of not using transactions (run with) so i grabbed
    
    Objectify datastore = ObjectifyService.begin();
    for (GalleryAppData appData:datastore.query(GalleryAppData.class).filter("userId",userId).offset(start).limit(count)) {
      
      GalleryApp gApp = new GalleryApp();
      makeGalleryApp(appData, gApp);
      apps.add(gApp);
    }
    return apps;
  }
  
  /**
   * when an gallery app is opened, this method is called to increment the #downloads
   * 
   */
  @Override
  public void incrementDownloads(final long galleryId) {
    
    try {
      runJobWithRetries(new JobRetryHelper() {
        @Override
        public void run(Objectify datastore) {
          GalleryAppData galleryAppData = datastore.find(galleryAppKey(galleryId));
          if (galleryAppData != null) {
            galleryAppData.numDownloads= galleryAppData.numDownloads+1;
            datastore.put(galleryAppData);
          }
        }
      });
    } catch (ObjectifyException e) {
       throw CrashReport.createAndLogError(LOG, null, "error in galleryStorageIo", e);
    }
  }

  /*
   * when an gallery app is updated, this method modifies title/description
   * 
   */
  @Override
  public void updateGalleryApp(final long galleryId, final String title,  final String description, 
    final String userId) {
    
    try {
      runJobWithRetries(new JobRetryHelper() {
        @Override
        public void run(Objectify datastore) {
          GalleryAppData galleryAppData = datastore.find(galleryAppKey(galleryId));
          if (galleryAppData != null) {
            galleryAppData.title= title;
            galleryAppData.description=description;
            datastore.put(galleryAppData);
          }
        }
      });
    } catch (ObjectifyException e) {
       throw CrashReport.createAndLogError(LOG, null, "error in galleryStorageIo", e);
    }
  }

  /* Gets a gallery app given a galleryId
   * NOTE: this is currently not being used and hasn't been tested
   */
  @Override
  public GalleryApp getGalleryApp(final long galleryId) {
    final GalleryApp gApp = new GalleryApp();
    try {
      runJobWithRetries(new JobRetryHelper() {
        @Override
        public void run(Objectify datastore) {
          GalleryAppData app = datastore.get(new Key<GalleryAppData>(GalleryAppData.class,galleryId));
          makeGalleryApp(app,gApp);
        }
      });
    } catch (ObjectifyException e) {
      throw CrashReport.createAndLogError(LOG, null,"gallery error", e);
    }
    return (gApp);
  }
  /**
   * add a comment to the comment list for a gallery app
   * 
   * 
   */
  @Override
  public long addComment(final long galleryId,final String userId, final String comment) {
    final Result<Long> theDate = new Result<Long>();
    try {
      runJobWithRetries(new JobRetryHelper() {
        @Override
        public void run(Objectify datastore) {
          GalleryCommentData commentData = new GalleryCommentData();
          long date = System.currentTimeMillis();
          commentData.comment = comment;
          commentData.userId = userId;
          commentData.galleryKey = galleryKey(galleryId);
          commentData.dateCreated=date;
          theDate.t=date;
          
          datastore.put(commentData);
        }
      });
    } catch (ObjectifyException e) {
       throw CrashReport.createAndLogError(LOG, null, "error in galleryStorageIo.addComment", e);
    }
    return theDate.t;
  }
  /**
   * get all the comments for a given galleryId
   * @return list of GalleryComment
   * 
   */
  @Override
  public List<GalleryComment> getComments(final long galleryId) {
   final List<GalleryComment> comments = new ArrayList<GalleryComment>();
    try {
      runJobWithRetries(new JobRetryHelper() {
        @Override
        public void run(Objectify datastore) {
          Key<GalleryAppData> galleryKey = galleryKey(galleryId);
          for (GalleryCommentData commentData : datastore.query(GalleryCommentData.class).ancestor(galleryKey).order("-dateCreated")) {
            GalleryComment galleryComment = new GalleryComment(galleryId,
                commentData.userId,commentData.comment,commentData.dateCreated);
            comments.add(galleryComment);
          }
        }
      });
    } catch (ObjectifyException e) {
        throw CrashReport.createAndLogError(LOG, null, "error in galleryStorageIo.getComments", e);
    }

    return comments;
  }
  
  /**
   * Converts a db object GalleryAppData into a shared GalleryApp that can be passed
   * around in client. Create the galleryApp first then send it here to get its data
   * 
   */
  private void makeGalleryApp(GalleryAppData appData, GalleryApp galleryApp) {
    galleryApp.setTitle (appData.title); 
    galleryApp.setProjectName(appData.projectName);
    galleryApp.setGalleryAppId(appData.id);
    galleryApp.setProjectId(appData.projectId);
    galleryApp.setDescription(appData.description);

    User developer = storageIo.getUser(appData.userId);
    galleryApp.setDeveloperName(developer.getUserName());
    galleryApp.setDeveloperId(appData.userId);
    galleryApp.setDownloads(appData.numDownloads);  
    galleryApp.setCreationDate(appData.dateCreated);
    galleryApp.setUpdateDate(appData.dateModified);

  }

  private static String collectGalleryAppErrorInfo(final String galleryAppId) {
    return "galleryApp=" + galleryAppId;
  }

  private Key<GalleryAppData> galleryAppKey(long galleryAppId) {
    return new Key<GalleryAppData>(GalleryAppData.class, galleryAppId);
  }
  /**
   * Call job.run() in a transaction and commit the transaction if no exceptions
   * occur. If we get a {@link java.util.ConcurrentModificationException}
   * or {@link com.google.appinventor.server.storage.ObjectifyException}
   * we will retry the job (at most {@code MAX_JOB_RETRIES times}).
   * Any other exception will cause the job to fail immediately.
   * @param job
   * @throws ObjectifyException
   */
  @VisibleForTesting
  void runJobWithRetries(JobRetryHelper job) throws ObjectifyException {
    int tries = 0;
    while (tries <= MAX_JOB_RETRIES) {
      Objectify datastore = ObjectifyService.beginTransaction();
      try {
        job.run(datastore);
        datastore.getTxn().commit();
        break;
      } catch (ConcurrentModificationException ex) {
        job.onNonFatalError();
        LOG.log(Level.WARNING, "Optimistic concurrency failure", ex);
      } catch (ObjectifyException oe) {
        // maybe this should be a fatal error? I think the only thing
        // that creates this exception (other than this method) is uploadToBlobstore
        job.onNonFatalError();
      } finally {
        if (datastore.getTxn().isActive()) {
          try {
            datastore.getTxn().rollback();
          } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Transaction rollback failed", e);
          }
        }
      }
      tries++;
    }
    if (tries > MAX_JOB_RETRIES) {
      throw new ObjectifyException("Couldn't commit job after max retries.");
    }
  }

  private Key<GalleryAppData> galleryKey(long galleryId) {
    return new Key<GalleryAppData>(GalleryAppData.class, galleryId);
  }

}
