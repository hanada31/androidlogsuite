package com.androidlogsuite.task;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import com.androidlogsuite.util.Log;
import com.androidlogsuite.util.ThreadsPool;

public class TaskCenter implements Runnable {

    private static String TAG = TaskCenter.class.toString();
    private static Selector mSelector;
    private HashMap<SelectionKey, Task> mTasks;

    
    private static TaskCenter gTaskCenter = new TaskCenter();
    volatile private boolean mbCenterStopped = false;

    public static TaskCenter getTaskCenter() {
        return gTaskCenter;
    }

    private TaskCenter() {
        mTasks = new HashMap<SelectionKey, Task>();
        try {
            mSelector = Selector.open();
        } catch (Exception e) {
        }

    }

    public void start() {
        ThreadsPool.getThreadsPool().addTask(this);
    }

    public void stop() {
        synchronized (mTasks) {
            try {
                mSelector.close();
                mbCenterStopped = true;
                mTasks.notifyAll();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    public Selector getSelector() {
        return mSelector;
    }

    public void addSocketChannel(SelectionKey key, AdbTask client) {
        synchronized (mTasks) {
            mTasks.put(key, client);
            try {
                mTasks.notifyAll();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    public void removeSocketChannel(SelectionKey key) {
        synchronized (mTasks) {
            // it suppose AdbTask should be released before calling
            // removeSocketChannel
            mTasks.remove(key);
        }

    }

    public void run() {
        ArrayList<Task> needCloseTasks = new ArrayList<Task>();
        while (mbCenterStopped == false) {
            try {
                needCloseTasks.clear();
                // prepareToRun is protected by outside lock;
                synchronized (mTasks) {
                    if (mTasks.size() == 0) {
                        try {
                            mTasks.wait();
                        } catch (Exception e) {
                            // TODO: handle exception
                        }
                    }
                    if (mbCenterStopped)
                        break;
                    Collection<Task> clients = mTasks.values();
                    for (Task client : clients) {
                        if (client.prepareToRun() == false) {
                            needCloseTasks.add(client);
                        }
                    }
                }

                for (Task task : needCloseTasks) {
                    task.close();
                }

                mSelector.select();
                if (mbCenterStopped) {
                    break;
                }

                Iterator<SelectionKey> it = mSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = (SelectionKey) it.next();
                    handleTask(key);
                    it.remove();
                }
            } catch (Exception e) {
                Log.d(TAG, e.getMessage());
                e.printStackTrace();
                break;
            }
        }
        Log.d(TAG, "remove unfinished tasks");
        for (Task task : needCloseTasks) {
            task.close();
        }
        Collection<Task> clients = mTasks.values();
        for (Task client : clients) {
            client.close();
        }
    }

    private void handleTask(SelectionKey key) {
        Task task = null;
        synchronized (mTasks) {
            task = mTasks.get(key);
            if (task == null) {
                // it means adb tasks has been canceled
                // Log.d(TAG, "Wow, adbTask has been canceled");
                return;
            }
        }
        // run task
        boolean bTaskFinished = task.run();

        if (bTaskFinished) {
            task.close();
        }
    }
}