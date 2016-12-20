/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.debugger.memory.tracking;

import com.intellij.debugger.DebuggerManager;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl;
import com.intellij.debugger.memory.component.CreationPositionTracker;
import com.intellij.debugger.memory.component.InstancesTracker;
import com.intellij.debugger.memory.event.InstancesTrackerListener;
import com.intellij.debugger.memory.utils.StackFrameDescriptor;
import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType;
import com.intellij.debugger.ui.breakpoints.LineBreakpoint;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.impl.XDebuggerManagerImpl;
import com.intellij.xdebugger.impl.breakpoints.LineBreakpointState;
import com.intellij.xdebugger.impl.breakpoints.XLineBreakpointImpl;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties;

import java.util.*;
import java.util.stream.Collectors;

public class ConstructorInstancesTracker implements TrackerForNewInstances, Disposable, BackgroundTracker {
  private static final int TRACKED_INSTANCES_LIMIT = 2000;
  private final ReferenceType myReference;
  private final XDebugSession myDebugSession;
  private final CreationPositionTracker myPositionTracker;
  private final DebugProcessImpl myDebugProcess;
  private final MyConstructorBreakpoints myBreakpoint;

  @Nullable
  private HashSet<ObjectReference> myNewObjects = null;

  @NotNull
  private HashSet<ObjectReference> myTrackedObjects = new HashSet<>();

  private volatile boolean myIsBackgroundMode;
  private volatile boolean myIsBackgroundTrackingEnabled;

  public ConstructorInstancesTracker(@NotNull ReferenceType ref,
                                     @NotNull XDebugSession debugSession) {
    myReference = ref;
    myDebugSession = debugSession;
    myPositionTracker = CreationPositionTracker.getInstance(debugSession.getProject());
    Project project = debugSession.getProject();
    myIsBackgroundTrackingEnabled = InstancesTracker.getInstance(myDebugSession.getProject())
      .isBackgroundTrackingEnabled();

    InstancesTracker.getInstance(myDebugSession.getProject()).addTrackerListener(new InstancesTrackerListener() {
      @Override
      public void backgroundTrackingValueChanged(boolean newState) {
        if (myIsBackgroundTrackingEnabled != newState) {
          myIsBackgroundTrackingEnabled = newState;
          myDebugProcess.getManagerThread().schedule(new DebuggerCommandImpl() {
            @Override
            protected void action() throws Exception {
              if (newState) {
                myBreakpoint.enable();
              }
              else {
                myBreakpoint.disable();
              }
            }
          });
        }
      }
    }, this);

    myDebugProcess = (DebugProcessImpl)DebuggerManager.getInstance(project)
      .getDebugProcess(debugSession.getDebugProcess().getProcessHandler());
    JavaLineBreakpointType breakPointType = new JavaLineBreakpointType();

    XBreakpoint bpn = new XLineBreakpointImpl<>(breakPointType,
                                                ((XDebuggerManagerImpl)XDebuggerManager.getInstance(project)).getBreakpointManager(),
                                                new JavaLineBreakpointProperties(),
                                                new LineBreakpointState<>());

    myBreakpoint = new MyConstructorBreakpoints(project, bpn);
    myBreakpoint.createRequestForPreparedClass(myDebugProcess, myReference);
  }

  public void obsolete() {
    if (myNewObjects != null) {
      myNewObjects.forEach(ObjectReference::enableCollection);
    }

    myNewObjects = null;
    if (!myIsBackgroundMode || myIsBackgroundTrackingEnabled) {
      myBreakpoint.enable();
    }
    myPositionTracker.releaseBySession(myDebugSession);
  }

  public void commitTracked() {
    myNewObjects = myTrackedObjects;
    myTrackedObjects = new HashSet<>();
  }

  @NotNull
  @Override
  public List<ObjectReference> getNewInstances() {
    return myNewObjects == null ? Collections.EMPTY_LIST : new ArrayList<>(myNewObjects);
  }

  @Override
  public int getCount() {
    return myNewObjects == null ? 0 : myNewObjects.size();
  }

  public void enable() {
    myBreakpoint.enable();
  }

  public void disable() {
    myBreakpoint.disable();
  }

  @Override
  public boolean isReady() {
    return myNewObjects != null;
  }

  @Override
  public void dispose() {
    Disposer.dispose(myBreakpoint);
  }

  @Override
  public void setBackgroundMode(boolean isBackgroundMode) {
    if (myIsBackgroundMode == isBackgroundMode) {
      return;
    }

    myIsBackgroundMode = isBackgroundMode;
    if (isBackgroundMode) {
      doEnableBackgroundMode();
    }
    else {
      doDisableBackgroundMode();
    }
  }

  private void doDisableBackgroundMode() {
    myBreakpoint.enable();
  }

  private void doEnableBackgroundMode() {
    if (!myIsBackgroundTrackingEnabled) {
      myBreakpoint.disable();
    }
  }

  private final class MyConstructorBreakpoints extends LineBreakpoint<JavaLineBreakpointProperties> implements Disposable {
    private boolean myIsEnabled = false;
    private final List<BreakpointRequest> myRequests = new ArrayList<>();
    private volatile boolean myIsDeleted = false;

    MyConstructorBreakpoints(Project project, XBreakpoint xBreakpoint) {
      super(project, xBreakpoint);
      setVisible(false);
    }

    @Override
    protected void createRequestForPreparedClass(DebugProcessImpl debugProcess, ReferenceType classType) {
      classType.methods().stream().filter(Method::isConstructor).forEach(cons -> {
        Location loc = cons.location();
        BreakpointRequest breakpointRequest = debugProcess.getRequestsManager().createBreakpointRequest(this, loc);
        myRequests.add(breakpointRequest);
      });

      if (!myIsBackgroundMode || myIsBackgroundTrackingEnabled) {
        enable();
      }
    }

    @Override
    public void reload() {
    }

    @Override
    public void dispose() {
      myDebugProcess.getManagerThread().schedule(new DebuggerCommandImpl() {
        @Override
        protected void action() throws Exception {
          disable();
          myDebugProcess.getRequestsManager().deleteRequest(MyConstructorBreakpoints.this);
          myIsDeleted = true;
        }
      });
    }

    @Override
    public boolean processLocatableEvent(SuspendContextCommandImpl action, LocatableEvent event)
      throws EventProcessingException {
      try {
        SuspendContextImpl suspendContext = action.getSuspendContext();
        if (suspendContext != null) {
          ObjectReference thisRef = getThisObject(suspendContext, event);
          if (myReference.equals(thisRef.referenceType()) && myPositionTracker != null) {
            thisRef.disableCollection();
            myTrackedObjects.add(thisRef);
            ThreadReferenceProxyImpl threadReferenceProxy = suspendContext.getThread();
            List<StackFrameProxyImpl> stack = threadReferenceProxy == null ? null : threadReferenceProxy.frames();

            if (stack != null) {
              List<StackFrameDescriptor> stackFrameDescriptors = stack.stream().map(frame -> {
                try {
                  Location loc = frame.location();
                  String typeName = loc.declaringType().name();
                  String methodName = loc.method().name();
                  return new StackFrameDescriptor(typeName, methodName, loc.lineNumber());
                }
                catch (EvaluateException e) {
                  return null;
                }
              }).filter(Objects::nonNull).collect(Collectors.toList());
              myPositionTracker.addStack(myDebugSession, thisRef, stackFrameDescriptors);
            }
          }
        }
      }
      catch (EvaluateException e) {
        return false;
      }

      if (myTrackedObjects.size() >= TRACKED_INSTANCES_LIMIT) {
        disable();
      }

      return false;
    }

    void enable() {
      if (!myIsEnabled && !myIsDeleted) {
        myRequests.forEach(EventRequest::enable);
        myIsEnabled = true;
      }
    }

    void disable() {
      if (myIsEnabled && !myIsDeleted) {
        myRequests.forEach(EventRequest::disable);
        myIsEnabled = false;
      }
    }
  }
}
