/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.instrumentation;

import com.oracle.truffle.api.CompilerDirectives;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.impl.Accessor;
import com.oracle.truffle.api.impl.Accessor.Nodes;
import com.oracle.truffle.api.impl.DispatchOutputStream;
import com.oracle.truffle.api.instrumentation.InstrumentableFactory.WrapperNode;
import com.oracle.truffle.api.instrumentation.ProbeNode.EventChainNode;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Env;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Central coordinator class for the Truffle instrumentation framework. Allocated once per
 * {@linkplain com.oracle.truffle.api.vm.PolyglotEngine engine}.
 */
final class InstrumentationHandler {

    /* Enable trace output to stdout. */
    private static final boolean TRACE = Boolean.getBoolean("truffle.instrumentation.trace");

    private final Object sourceVM;

    /*
     * The contract is the following: "sources" and "sourcesList" can only be accessed while
     * synchronized on "sources". both will only be lazily initialized from "loadedRoots" when the
     * first sourceBindings is added, by calling lazyInitializeSourcesList(). "sourcesList" will be
     * null as long as the sources haven't been initialized.
     */
    private final Map<Source, Void> sources = Collections.synchronizedMap(new WeakHashMap<Source, Void>());
    /* Load order needs to be preserved for sources, thats why we store sources again in a list. */
    private volatile Collection<Source> sourcesList;

    private final Collection<RootNode> loadedRoots = new WeakAsyncList<>(256);
    private final Collection<RootNode> executedRoots = new WeakAsyncList<>(64);
    private final Collection<AllocationReporter> allocationReporters = new WeakAsyncList<>(16);

    private final Collection<EventBinding.Source<?>> executionBindings = new EventBindingList<>(8);
    private final Collection<EventBinding.Source<?>> sourceSectionBindings = new EventBindingList<>(8);
    private final Collection<EventBinding.Source<?>> sourceBindings = new EventBindingList<>(8);
    private final Collection<EventBinding<? extends OutputStream>> outputStdBindings = new EventBindingList<>(1);
    private final Collection<EventBinding<? extends OutputStream>> outputErrBindings = new EventBindingList<>(1);
    private final Collection<EventBinding.Allocation<? extends AllocationListener>> allocationBindings = new EventBindingList<>(2);
    private final Collection<EventBinding<? extends ContextsListener>> contextsBindings = new EventBindingList<>(8);
    private final Collection<EventBinding<? extends ThreadsListener>> threadsBindings = new EventBindingList<>(8);

    /*
     * Fast lookup of instrumenter instances based on a key provided by the accessor.
     */
    private final ConcurrentHashMap<Object, AbstractInstrumenter> instrumenterMap = new ConcurrentHashMap<>();

    private final DispatchOutputStream out;
    private final DispatchOutputStream err;
    private final InputStream in;
    private final Map<Class<?>, Set<Class<?>>> cachedProvidedTags = new ConcurrentHashMap<>();

    private final EngineInstrumenter engineInstrumenter;

    private InstrumentationHandler(Object sourceVM, DispatchOutputStream out, DispatchOutputStream err, InputStream in) {
        this.sourceVM = sourceVM;
        this.out = out;
        this.err = err;
        this.in = in;
        this.engineInstrumenter = new EngineInstrumenter();
    }

    Object getSourceVM() {
        return sourceVM;
    }

    void onLoad(RootNode root) {
        if (!AccessorInstrumentHandler.nodesAccess().isInstrumentable(root)) {
            return;
        }
        assert root.getLanguageInfo() != null;
        Source source = null;
        synchronized (sources) {
            if (!sourceBindings.isEmpty()) {
                // we'll add to the sourcesList, so it needs to be initialized
                lazyInitializeSourcesList();

                SourceSection sourceSection = root.getSourceSection();
                if (sourceSection != null) {
                    // notify sources
                    source = sourceSection.getSource();
                    if (!sources.containsKey(source)) {
                        sources.put(source, null);
                        sourcesList.add(source);
                    } else {
                        // The source is not new
                        source = null;
                    }
                }
            }
            loadedRoots.add(root);
        }
        // we don't want to invoke foreign code while we are holding a lock to avoid
        // deadlocks.
        if (source != null) {
            notifySourceBindingsLoaded(sourceBindings, source);
        }

        // fast path no bindings attached
        if (!sourceSectionBindings.isEmpty()) {
            visitRoot(root, root, new NotifyLoadedListenerVisitor(sourceSectionBindings), false);
        }

    }

    void onFirstExecution(RootNode root) {
        if (!AccessorInstrumentHandler.nodesAccess().isInstrumentable(root)) {
            return;
        }
        assert root.getLanguageInfo() != null;
        executedRoots.add(root);

        // fast path no bindings attached
        if (executionBindings.isEmpty()) {
            return;
        }

        visitRoot(root, root, new InsertWrappersVisitor(executionBindings), false);
    }

    void initializeInstrument(Object vmObject, Class<?> instrumentClass) {
        Env env = new Env(vmObject, out, err, in);
        env.instrumenter = new InstrumentClientInstrumenter(env, instrumentClass);

        if (TRACE) {
            trace("Initialize instrument class %s %n", instrumentClass);
        }
        try {
            env.instrumenter.instrument = (TruffleInstrument) instrumentClass.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            failInstrumentInitialization(env, String.format("Failed to create new instrumenter class %s", instrumentClass.getName()), e);
            return;
        }

        if (TRACE) {
            trace("Initialized instrument %s class %s %n", env.instrumenter.instrument, instrumentClass);
        }

        addInstrumenter(vmObject, env.instrumenter);
    }

    void createInstrument(Object vmObject, String[] expectedServices, OptionValues optionValues) {
        InstrumentClientInstrumenter instrumenter = ((InstrumentClientInstrumenter) instrumenterMap.get(vmObject));
        instrumenter.env.options = optionValues;
        instrumenter.create(expectedServices);
    }

    void disposeInstrumenter(Object key, boolean cleanupRequired) {
        AbstractInstrumenter disposedInstrumenter = instrumenterMap.remove(key);
        if (disposedInstrumenter == null) {
            throw new AssertionError("Instrumenter already disposed.");
        }
        if (TRACE) {
            trace("BEGIN: Dispose instrumenter %n", key);
        }
        disposedInstrumenter.dispose();

        if (cleanupRequired) {
            Collection<EventBinding.Source<?>> disposedExecutionBindings = filterBindingsForInstrumenter(executionBindings, disposedInstrumenter);
            if (!disposedExecutionBindings.isEmpty()) {
                visitRoots(executedRoots, new DisposeWrappersWithBindingVisitor(disposedExecutionBindings));
            }
            disposeBindingsBulk(disposedExecutionBindings);
            disposeBindingsBulk(filterBindingsForInstrumenter(sourceSectionBindings, disposedInstrumenter));
            disposeBindingsBulk(filterBindingsForInstrumenter(sourceBindings, disposedInstrumenter));
            disposeOutputBindingsBulk(out, outputStdBindings);
            disposeOutputBindingsBulk(err, outputErrBindings);
        }
        if (TRACE) {
            trace("END: Disposed instrumenter %n", key);
        }
    }

    private static void disposeBindingsBulk(Collection<EventBinding.Source<?>> list) {
        for (EventBinding<?> binding : list) {
            binding.disposeBulk();
        }
    }

    private static void disposeOutputBindingsBulk(DispatchOutputStream dos, Collection<EventBinding<? extends OutputStream>> list) {
        for (EventBinding<? extends OutputStream> binding : list) {
            AccessorInstrumentHandler.engineAccess().detachOutputConsumer(dos, binding.getElement());
            binding.disposeBulk();
        }
    }

    Instrumenter forLanguage(LanguageInfo info) {
        return new LanguageClientInstrumenter<>(info);
    }

    <T> EventBinding<T> addExecutionBinding(EventBinding.Source<T> binding) {
        if (TRACE) {
            trace("BEGIN: Adding execution binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        this.executionBindings.add(binding);

        if (!executedRoots.isEmpty()) {
            visitRoots(executedRoots, new InsertWrappersWithBindingVisitor(binding));
        }

        if (TRACE) {
            trace("END: Added execution binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    <T> EventBinding<T> addSourceSectionBinding(EventBinding.Source<T> binding, boolean notifyLoaded) {
        if (TRACE) {
            trace("BEGIN: Adding binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        this.sourceSectionBindings.add(binding);
        if (notifyLoaded) {
            if (!loadedRoots.isEmpty()) {
                visitRoots(loadedRoots, new NotifyLoadedWithBindingVisitor(binding));
            }
        }

        if (TRACE) {
            trace("END: Added binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    <T> EventBinding<T> addSourceBinding(EventBinding.Source<T> binding, boolean notifyLoaded) {
        if (TRACE) {
            trace("BEGIN: Adding source binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        this.sourceBindings.add(binding);
        if (notifyLoaded) {
            synchronized (sources) {
                lazyInitializeSourcesList();
            }
            for (Source source : sourcesList) {
                notifySourceBindingLoaded(binding, source);
            }
        }

        if (TRACE) {
            trace("END: Added source binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        return binding;
    }

    <T extends OutputStream> EventBinding<T> addOutputBinding(EventBinding<T> binding, boolean errorOutput) {
        if (TRACE) {
            String kind = (errorOutput) ? "error" : "standard";
            trace("BEGIN: Adding " + kind + " output binding %s%n", binding.getElement());
        }

        if (errorOutput) {
            this.outputErrBindings.add(binding);
            AccessorInstrumentHandler.engineAccess().attachOutputConsumer(this.err, binding.getElement());
        } else {
            this.outputStdBindings.add(binding);
            AccessorInstrumentHandler.engineAccess().attachOutputConsumer(this.out, binding.getElement());
        }

        if (TRACE) {
            String kind = (errorOutput) ? "error" : "standard";
            trace("END: Added " + kind + " output binding %s%n", binding.getElement());
        }

        return binding;
    }

    private <T extends AllocationListener> EventBinding<T> addAllocationBinding(EventBinding.Allocation<T> binding) {
        if (TRACE) {
            trace("BEGIN: Adding allocation binding %s%n", binding.getElement());
        }

        this.allocationBindings.add(binding);
        for (AllocationReporter allocationReporter : allocationReporters) {
            if (binding.getAllocationFilter().contains(allocationReporter.language)) {
                allocationReporter.addListener(binding.getElement());
            }
        }

        if (TRACE) {
            trace("END: Added allocation binding %s%n", binding.getElement());
        }
        return binding;
    }

    private <T extends ContextsListener> EventBinding<T> addContextsBinding(EventBinding<T> binding, boolean includeActiveContexts) {
        if (TRACE) {
            trace("BEGIN: Adding contexts binding %s%n", binding.getElement());
        }

        contextsBindings.add(binding);
        if (includeActiveContexts) {
            Accessor.EngineSupport engineAccess = InstrumentationHandler.AccessorInstrumentHandler.engineAccess();
            engineAccess.reportAllLanguageContexts(sourceVM, binding.getElement());
        }

        if (TRACE) {
            trace("END: Added contexts binding %s%n", binding.getElement());
        }
        return binding;
    }

    private <T extends ThreadsListener> EventBinding<T> addThreadsBinding(EventBinding<T> binding, boolean includeStartedThreads) {
        if (TRACE) {
            trace("BEGIN: Adding threads binding %s%n", binding.getElement());
        }

        threadsBindings.add(binding);
        if (includeStartedThreads) {
            Accessor.EngineSupport engineAccess = InstrumentationHandler.AccessorInstrumentHandler.engineAccess();
            engineAccess.reportAllContextThreads(sourceVM, binding.getElement());
        }

        if (TRACE) {
            trace("END: Added threads binding %s%n", binding.getElement());
        }
        return binding;
    }

    /**
     * Initializes sources and sourcesList by populating them from loadedRoots.
     */
    private void lazyInitializeSourcesList() {
        assert Thread.holdsLock(sources);
        if (sourcesList == null) {
            // build the sourcesList, we need it now
            sourcesList = new WeakAsyncList<>(16);
            for (RootNode root : loadedRoots) {
                SourceSection sourceSection = root.getSourceSection();
                if (sourceSection != null) {
                    // notify sources
                    Source source = sourceSection.getSource();
                    if (!sources.containsKey(source)) {
                        sources.put(source, null);
                        sourcesList.add(source);
                    }
                }
            }
        }
    }

    private void visitRoots(Collection<RootNode> roots, AbstractNodeVisitor addBindingsVisitor) {
        for (RootNode root : roots) {
            visitRoot(root, root, addBindingsVisitor, false);
        }
    }

    @SuppressWarnings("deprecation")
    void disposeBinding(EventBinding<?> binding) {
        if (TRACE) {
            trace("BEGIN: Dispose binding %s, %s%n", binding.getFilter(), binding.getElement());
        }

        if (binding instanceof EventBinding.Source) {
            EventBinding.Source<?> sourceBinding = (EventBinding.Source<?>) binding;
            if (sourceBinding.isExecutionEvent()) {
                visitRoots(executedRoots, new DisposeWrappersVisitor(sourceBinding));
            }
        } else if (binding instanceof EventBinding.Allocation) {
            EventBinding.Allocation<?> allocationBinding = (EventBinding.Allocation<?>) binding;
            AllocationListener l = (AllocationListener) binding.getElement();
            for (AllocationReporter allocationReporter : allocationReporters) {
                if (allocationBinding.getAllocationFilter().contains(allocationReporter.language)) {
                    allocationReporter.removeListener(l);
                }
            }
        } else {
            Object elm = binding.getElement();
            if (elm instanceof OutputStream) {
                if (outputErrBindings.contains(binding)) {
                    AccessorInstrumentHandler.engineAccess().detachOutputConsumer(err, (OutputStream) elm);
                } else if (outputStdBindings.contains(binding)) {
                    AccessorInstrumentHandler.engineAccess().detachOutputConsumer(out, (OutputStream) elm);
                }
            } else if (elm instanceof ContextsListener) {
                // binding disposed
            } else if (elm instanceof ThreadsListener) {
                // binding disposed
            } else {
                assert false : "Unexpected binding " + binding + " with element " + elm;
            }
        }

        if (TRACE) {
            trace("END: Disposed binding %s, %s%n", binding.getFilter(), binding.getElement());
        }
    }

    EventChainNode createBindings(ProbeNode probeNodeImpl) {
        EventContext context = probeNodeImpl.getContext();
        SourceSection sourceSection = context.getInstrumentedSourceSection();
        if (TRACE) {
            trace("BEGIN: Lazy update for %s%n", sourceSection);
        }

        RootNode rootNode = probeNodeImpl.getRootNode();
        Node instrumentedNode = probeNodeImpl.findWrapper().getDelegateNode();
        Set<Class<?>> providedTags = getProvidedTags(rootNode);
        EventChainNode root = null;
        EventChainNode parent = null;

        for (EventBinding.Source<?> binding : executionBindings) {
            if (binding.isInstrumentedFull(providedTags, rootNode, instrumentedNode, sourceSection)) {
                if (TRACE) {
                    trace("  Found binding %s, %s%n", binding.getFilter(), binding.getElement());
                }
                EventChainNode next = probeNodeImpl.createEventChainCallback(binding);
                if (next == null) {
                    continue;
                }

                if (root == null) {
                    root = next;
                } else {
                    assert parent != null;
                    parent.setNext(next);
                }
                parent = next;
            }
        }

        if (TRACE) {
            trace("END: Lazy updated for %s%n", sourceSection);
        }
        return root;
    }

    public void onNodeInserted(RootNode rootNode, Node tree) {
        if (!sourceSectionBindings.isEmpty()) {
            visitRoot(rootNode, tree, new NotifyLoadedListenerVisitor(sourceSectionBindings), true);
        }
        if (!executionBindings.isEmpty()) {
            visitRoot(rootNode, tree, new InsertWrappersVisitor(executionBindings), true);
        }
    }

    private static void notifySourceBindingsLoaded(Collection<EventBinding.Source<?>> bindings, Source source) {
        for (EventBinding.Source<?> binding : bindings) {
            notifySourceBindingLoaded(binding, source);
        }
    }

    private static void notifySourceBindingLoaded(EventBinding.Source<?> binding, Source source) {
        if (!binding.isDisposed() && binding.isInstrumentedSource(source)) {
            try {
                ((LoadSourceListener) binding.getElement()).onLoad(new LoadSourceEvent(source));
            } catch (Throwable t) {
                if (binding.isLanguageBinding()) {
                    throw t;
                } else {
                    ProbeNode.exceptionEventForClientInstrument(binding, "onLoad", t);
                }
            }
        }
    }

    static void notifySourceSectionLoaded(EventBinding.Source<?> binding, Node node, SourceSection section) {
        LoadSourceSectionListener listener = (LoadSourceSectionListener) binding.getElement();
        try {
            listener.onLoad(new LoadSourceSectionEvent(section, node));
        } catch (Throwable t) {
            if (binding.isLanguageBinding()) {
                throw t;
            } else {
                ProbeNode.exceptionEventForClientInstrument(binding, "onLoad", t);
            }
        }
    }

    private void addInstrumenter(Object key, AbstractInstrumenter instrumenter) throws AssertionError {
        Object previousKey = instrumenterMap.putIfAbsent(key, instrumenter);
        if (previousKey != null) {
            throw new AssertionError("Instrumenter already present.");
        }
    }

    private static Collection<EventBinding.Source<?>> filterBindingsForInstrumenter(Collection<EventBinding.Source<?>> bindings, AbstractInstrumenter instrumenter) {
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }
        Collection<EventBinding.Source<?>> newBindings = new ArrayList<>();
        for (EventBinding.Source<?> binding : bindings) {
            if (binding.getInstrumenter() == instrumenter) {
                newBindings.add(binding);
            }
        }
        return newBindings;
    }

    @SuppressWarnings("unchecked")
    private void insertWrapper(Node instrumentableNode, SourceSection sourceSection) {
        final Node node = instrumentableNode;
        final Node parent = node.getParent();
        if (parent instanceof WrapperNode) {
            // already wrapped, need to invalidate the wrapper something changed
            invalidateWrapperImpl((WrapperNode) parent, node);
            return;
        }
        ProbeNode probe = new ProbeNode(InstrumentationHandler.this, sourceSection);
        WrapperNode wrapper;
        try {
            Class<?> factory = null;
            Class<?> currentClass = instrumentableNode.getClass();
            while (currentClass != null) {
                Instrumentable instrumentable = currentClass.getAnnotation(Instrumentable.class);
                if (instrumentable != null) {
                    factory = instrumentable.factory();
                    break;
                }
                currentClass = currentClass.getSuperclass();
            }

            if (factory == null) {
                if (TRACE) {
                    trace("No wrapper inserted for %s, section %s. Not annotated with @Instrumentable.%n", node, sourceSection);
                }
                // node or superclass is not annotated with @Instrumentable
                return;
            }

            if (TRACE) {
                trace("Insert wrapper for %s, section %s%n", node, sourceSection);
            }

            wrapper = ((InstrumentableFactory<Node>) factory.newInstance()).createWrapper(instrumentableNode, probe);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create wrapper node. ", e);
        }

        if (!(wrapper instanceof Node)) {
            throw new IllegalStateException(String.format("Implementation of %s must be a subclass of %s.", WrapperNode.class.getSimpleName(), Node.class.getSimpleName()));
        }

        final Node wrapperNode = (Node) wrapper;
        if (wrapperNode.getParent() != null) {
            throw new IllegalStateException(String.format("Instance of provided %s is already adopted by another parent.", WrapperNode.class.getSimpleName()));
        }
        if (parent == null) {
            throw new IllegalStateException(String.format("Instance of instrumentable %s is not adopted by a parent.", Node.class.getSimpleName()));
        }

        if (!NodeUtil.isReplacementSafe(parent, node, wrapperNode)) {
            throw new IllegalStateException(
                            String.format("WrapperNode implementation %s cannot be safely replaced in parent node class %s.", wrapperNode.getClass().getName(), parent.getClass().getName()));
        }

        node.replace(wrapperNode, "Insert instrumentation wrapper node.");
    }

    private <T extends ExecutionEventNodeFactory> EventBinding<T> attachFactory(AbstractInstrumenter instrumenter, SourceSectionFilter filter, T factory) {
        return addExecutionBinding(new EventBinding.Source<>(instrumenter, filter, factory, true));
    }

    private <T extends ExecutionEventListener> EventBinding<T> attachListener(AbstractInstrumenter instrumenter, SourceSectionFilter filter, T listener) {
        return addExecutionBinding(new EventBinding.Source<>(instrumenter, filter, listener, true));
    }

    private <T extends LoadSourceListener> EventBinding<T> attachSourceListener(AbstractInstrumenter abstractInstrumenter, SourceSectionFilter filter, T listener, boolean notifyLoaded) {
        return addSourceBinding(new EventBinding.Source<>(abstractInstrumenter, filter, listener, false), notifyLoaded);
    }

    private <T> EventBinding<T> attachSourceSectionListener(AbstractInstrumenter abstractInstrumenter, SourceSectionFilter filter, T listener, boolean notifyLoaded) {
        return addSourceSectionBinding(new EventBinding.Source<>(abstractInstrumenter, filter, listener, false), notifyLoaded);
    }

    private <T extends OutputStream> EventBinding<T> attachOutputConsumer(AbstractInstrumenter instrumenter, T stream, boolean errorOutput) {
        return addOutputBinding(new EventBinding<>(instrumenter, stream), errorOutput);
    }

    private <T extends AllocationListener> EventBinding<T> attachAllocationListener(AbstractInstrumenter instrumenter, AllocationEventFilter filter, T listener) {
        return addAllocationBinding(new EventBinding.Allocation<>(instrumenter, filter, listener));
    }

    private <T extends ContextsListener> EventBinding<T> attachContextsListener(AbstractInstrumenter instrumenter, T listener, boolean includeActiveContexts) {
        assert listener != null;
        return addContextsBinding(new EventBinding<>(instrumenter, listener), includeActiveContexts);
    }

    private <T extends ThreadsListener> EventBinding<T> attachThreadsListener(AbstractInstrumenter instrumenter, T listener, boolean includeStartedThreads) {
        assert listener != null;
        return addThreadsBinding(new EventBinding<>(instrumenter, listener), includeStartedThreads);
    }

    private void notifyContextCreated(TruffleContext context) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onContextCreated(context);
        }
    }

    private void notifyContextClosed(TruffleContext context) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onContextClosed(context);
        }
    }

    private void notifyLanguageContextCreated(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextCreated(context, language);
        }
    }

    private void notifyLanguageContextInitialized(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextInitialized(context, language);
        }
    }

    private void notifyLanguageContextFinalized(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextFinalized(context, language);
        }
    }

    private void notifyLanguageContextDisposed(TruffleContext context, LanguageInfo language) {
        for (EventBinding<? extends ContextsListener> binding : contextsBindings) {
            binding.getElement().onLanguageContextDisposed(context, language);
        }
    }

    private void notifyThreadStarted(TruffleContext context, Thread thread) {
        for (EventBinding<? extends ThreadsListener> binding : threadsBindings) {
            binding.getElement().onThreadInitialized(context, thread);
        }
    }

    private void notifyThreadFinished(TruffleContext context, Thread thread) {
        for (EventBinding<? extends ThreadsListener> binding : threadsBindings) {
            binding.getElement().onThreadDisposed(context, thread);
        }
    }

    Set<Class<?>> getProvidedTags(LanguageInfo language) {
        Nodes nodesAccess = AccessorInstrumentHandler.nodesAccess();
        TruffleLanguage<?> lang = nodesAccess.getLanguageSpi(language);
        if (lang == null) {
            return Collections.emptySet();
        }
        Class<?> languageClass = lang.getClass();
        Set<Class<?>> tags = cachedProvidedTags.get(languageClass);
        if (tags == null) {
            ProvidedTags languageTags = languageClass.getAnnotation(ProvidedTags.class);
            List<Class<?>> languageTagsList = languageTags != null ? Arrays.asList(languageTags.value()) : Collections.<Class<?>> emptyList();
            tags = Collections.unmodifiableSet(new HashSet<>(languageTagsList));
            cachedProvidedTags.put(languageClass, tags);
        }
        return tags;
    }

    Set<Class<?>> getProvidedTags(Node root) {
        return getProvidedTags(root.getRootNode().getLanguageInfo());
    }

    static boolean isInstrumentableNode(Node node, SourceSection sourceSection) {
        return !(node instanceof WrapperNode) && !(node instanceof RootNode) && sourceSection != null;
    }

    private static void trace(String message, Object... args) {
        PrintStream out = System.out;
        out.printf(message, args);
    }

    private void visitRoot(RootNode root, final Node node, final AbstractNodeVisitor visitor, boolean forceRootBitComputation) {
        if (TRACE) {
            trace("BEGIN: Visit root %s for %s%n", root.toString(), visitor);
        }

        visitor.root = root;
        visitor.providedTags = getProvidedTags(root);
        visitor.rootSourceSection = root.getSourceSection();
        visitor.rootBits = RootNodeBits.get(visitor.root);

        if (visitor.shouldVisit() || forceRootBitComputation) {
            if (forceRootBitComputation) {
                visitor.computingRootNodeBits = RootNodeBits.isUninitialized(visitor.rootBits) ? RootNodeBits.getAll() : visitor.rootBits;
            } else if (RootNodeBits.isUninitialized(visitor.rootBits)) {
                visitor.computingRootNodeBits = RootNodeBits.getAll();
            }

            if (TRACE) {
                trace("BEGIN: Traverse root %s for %s%n", root.toString(), visitor);
            }
            node.accept(visitor);
            if (TRACE) {
                trace("END: Traverse root %s for %s%n", root.toString(), visitor);
            }

            if (!RootNodeBits.isUninitialized(visitor.computingRootNodeBits)) {
                RootNodeBits.set(visitor.root, visitor.computingRootNodeBits);
            }
        }

        if (TRACE) {
            trace("END: Visited root %s for %s%n", root.toString(), visitor);
        }
    }

    static void removeWrapper(ProbeNode node) {
        if (TRACE) {
            trace("Remove wrapper for %s%n", node.getContext().getInstrumentedSourceSection());
        }
        WrapperNode wrapperNode = node.findWrapper();
        ((Node) wrapperNode).replace(wrapperNode.getDelegateNode());
    }

    private static void invalidateWrapper(Node node) {
        Node parent = node.getParent();
        if (!(parent instanceof WrapperNode)) {
            // not yet wrapped
            return;
        }
        invalidateWrapperImpl((WrapperNode) parent, node);
    }

    private static void invalidateWrapperImpl(WrapperNode parent, Node node) {
        ProbeNode probeNode = parent.getProbeNode();
        if (TRACE) {
            SourceSection section = probeNode.getContext().getInstrumentedSourceSection();
            trace("Invalidate wrapper for %s, section %s %n", node, section);
        }
        if (probeNode != null) {
            probeNode.invalidate();
        }
    }

    static boolean hasTagImpl(Set<Class<?>> providedTags, Node node, Class<?> tag) {
        if (providedTags.contains(tag)) {
            return AccessorInstrumentHandler.nodesAccess().isTaggedWith(node, tag);
        }
        return false;
    }

    static Instrumentable getInstrumentable(Node node) {
        Instrumentable instrumentable = node.getClass().getAnnotation(Instrumentable.class);
        if (instrumentable != null && !(node instanceof WrapperNode)) {
            return instrumentable;
        }
        return null;
    }

    private <T> T lookup(Object key, Class<T> type) {
        AbstractInstrumenter value = instrumenterMap.get(key);
        return value == null ? null : value.lookup(this, type);
    }

    private AllocationReporter getAllocationReporter(LanguageInfo info) {
        AllocationReporter allocationReporter = new AllocationReporter(info);
        allocationReporters.add(allocationReporter);
        for (EventBinding.Allocation<? extends AllocationListener> binding : allocationBindings) {
            if (binding.getAllocationFilter().contains(info)) {
                allocationReporter.addListener(binding.getElement());
            }
        }
        return allocationReporter;
    }

    static void failInstrumentInitialization(Env env, String message, Throwable t) {
        Exception exception = new Exception(message, t);
        PrintStream stream = new PrintStream(env.err());
        exception.printStackTrace(stream);
    }

    private abstract static class AbstractNodeVisitor implements NodeVisitor {

        RootNode root;
        SourceSection rootSourceSection;
        Set<Class<?>> providedTags;

        /* cached root bits read from the root node. value is reliable. */
        int rootBits;
        /* temporary field for currently computing root bits. value is not reliable. */
        int computingRootNodeBits;

        abstract boolean shouldVisit();

        final void computeRootBits(SourceSection sourceSection) {
            int bits = computingRootNodeBits;
            if (RootNodeBits.isUninitialized(bits)) {
                return;
            }

            if (sourceSection != null) {
                if (RootNodeBits.isNoSourceSection(bits)) {
                    bits = RootNodeBits.setHasSourceSection(bits);
                }
                if (rootSourceSection != null) {
                    if (RootNodeBits.isSourceSectionsHierachical(bits)) {
                        if (sourceSection.getCharIndex() < rootSourceSection.getCharIndex() //
                                        || sourceSection.getCharEndIndex() > rootSourceSection.getCharEndIndex()) {
                            bits = RootNodeBits.setSourceSectionsUnstructured(bits);
                        }
                    }
                    if (RootNodeBits.isSameSource(bits) && rootSourceSection.getSource() != sourceSection.getSource()) {
                        bits = RootNodeBits.setHasDifferentSource(bits);
                    }
                } else {
                    bits = RootNodeBits.setSourceSectionsUnstructured(bits);
                    bits = RootNodeBits.setHasDifferentSource(bits);
                }
            }
            computingRootNodeBits = bits;
        }

    }

    private abstract class AbstractBindingVisitor extends AbstractNodeVisitor {

        protected final EventBinding.Source<?> binding;

        AbstractBindingVisitor(EventBinding.Source<?> binding) {
            this.binding = binding;
        }

        @Override
        boolean shouldVisit() {
            RootNode localRoot = root;
            SourceSection localRootSourceSection = rootSourceSection;
            int localRootBits = rootBits;
            return binding.isInstrumentedRoot(providedTags, localRoot, localRootSourceSection, localRootBits);
        }

        public final boolean visit(Node node) {
            SourceSection sourceSection = node.getSourceSection();
            if (isInstrumentableNode(node, sourceSection)) {
                computeRootBits(sourceSection);
                if (binding.isInstrumentedLeaf(providedTags, node, sourceSection)) {
                    if (TRACE) {
                        traceFilterCheck("hit", providedTags, binding, node, sourceSection);
                    }
                    visitInstrumented(node, sourceSection);
                } else {
                    if (TRACE) {
                        traceFilterCheck("miss", providedTags, binding, node, sourceSection);
                    }
                }
            }
            return true;
        }

        protected abstract void visitInstrumented(Node node, SourceSection section);
    }

    @SuppressWarnings("deprecation")
    private static void traceFilterCheck(String result, Set<Class<?>> providedTags, EventBinding<?> binding, Node node, SourceSection sourceSection) {
        Set<Class<?>> tags = binding.getFilter().getReferencedTags();
        Set<Class<?>> containedTags = new HashSet<>();
        for (Class<?> tag : tags) {
            if (hasTagImpl(providedTags, node, tag)) {
                containedTags.add(tag);
            }
        }
        trace("  Filter %4s %s section:%s tags:%s%n", result, binding.getFilter(), sourceSection, containedTags);
    }

    private abstract class AbstractBindingsVisitor extends AbstractNodeVisitor {

        private final Collection<EventBinding.Source<?>> bindings;
        private final boolean visitForEachBinding;

        AbstractBindingsVisitor(Collection<EventBinding.Source<?>> bindings, boolean visitForEachBinding) {
            this.bindings = bindings;
            this.visitForEachBinding = visitForEachBinding;
        }

        @Override
        boolean shouldVisit() {
            if (bindings.isEmpty()) {
                return false;
            }
            RootNode localRoot = root;
            SourceSection localRootSourceSection = rootSourceSection;
            int localRootBits = rootBits;

            for (EventBinding.Source<?> binding : bindings) {
                if (binding.isInstrumentedRoot(providedTags, localRoot, localRootSourceSection, localRootBits)) {
                    return true;
                }
            }
            return false;
        }

        public final boolean visit(Node node) {
            SourceSection sourceSection = node.getSourceSection();
            if (isInstrumentableNode(node, sourceSection)) {
                computeRootBits(sourceSection);
                // no locking required for these atomic reference arrays
                for (EventBinding.Source<?> binding : bindings) {
                    if (binding.isInstrumentedFull(providedTags, root, node, sourceSection)) {
                        if (TRACE) {
                            traceFilterCheck("hit", providedTags, binding, node, sourceSection);
                        }
                        visitInstrumented(binding, node, sourceSection);
                        if (!visitForEachBinding) {
                            break;
                        }
                    } else {
                        if (TRACE) {
                            traceFilterCheck("miss", providedTags, binding, node, sourceSection);
                        }
                    }
                }
            }
            return true;
        }

        protected abstract void visitInstrumented(EventBinding.Source<?> binding, Node node, SourceSection section);

    }

    /* Insert wrappers for a single bindings. */
    private final class InsertWrappersWithBindingVisitor extends AbstractBindingVisitor {

        InsertWrappersWithBindingVisitor(EventBinding.Source<?> filter) {
            super(filter);
        }

        @Override
        protected void visitInstrumented(Node node, SourceSection section) {
            insertWrapper(node, section);
        }

    }

    private final class DisposeWrappersVisitor extends AbstractBindingVisitor {

        DisposeWrappersVisitor(EventBinding.Source<?> binding) {
            super(binding);
        }

        @Override
        protected void visitInstrumented(Node node, SourceSection section) {
            invalidateWrapper(node);
        }
    }

    private final class InsertWrappersVisitor extends AbstractBindingsVisitor {

        InsertWrappersVisitor(Collection<EventBinding.Source<?>> bindings) {
            super(bindings, false);
        }

        @Override
        protected void visitInstrumented(EventBinding.Source<?> binding, Node node, SourceSection section) {
            insertWrapper(node, section);
        }
    }

    private final class DisposeWrappersWithBindingVisitor extends AbstractBindingsVisitor {

        DisposeWrappersWithBindingVisitor(Collection<EventBinding.Source<?>> bindings) {
            super(bindings, false);
        }

        @Override
        protected void visitInstrumented(EventBinding.Source<?> binding, Node node, SourceSection section) {
            invalidateWrapper(node);
        }

    }

    private final class NotifyLoadedWithBindingVisitor extends AbstractBindingVisitor {

        NotifyLoadedWithBindingVisitor(EventBinding.Source<?> binding) {
            super(binding);
        }

        @Override
        protected void visitInstrumented(Node node, SourceSection section) {
            notifySourceSectionLoaded(binding, node, section);
        }

    }

    private final class NotifyLoadedListenerVisitor extends AbstractBindingsVisitor {

        NotifyLoadedListenerVisitor(Collection<EventBinding.Source<?>> bindings) {
            super(bindings, true);
        }

        @Override
        protected void visitInstrumented(EventBinding.Source<?> binding, Node node, SourceSection section) {
            notifySourceSectionLoaded(binding, node, section);
        }
    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleInstrument external clients} of
     * instrumentation.
     */
    final class InstrumentClientInstrumenter extends AbstractInstrumenter {

        private final Class<?> instrumentClass;
        private Object[] services;
        private TruffleInstrument instrument;
        private final Env env;

        InstrumentClientInstrumenter(Env env, Class<?> instrumentClass) {
            this.instrumentClass = instrumentClass;
            this.env = env;
        }

        @Override
        boolean isInstrumentableSource(Source source) {
            return true;
        }

        @Override
        boolean isInstrumentableRoot(RootNode rootNode) {
            return true;
        }

        @Override
        public Set<Class<?>> queryTags(Node node) {
            return queryTagsImpl(node, null);
        }

        @Override
        void verifyFilter(SourceSectionFilter filter) {
        }

        Class<?> getInstrumentClass() {
            return instrumentClass;
        }

        Env getEnv() {
            return env;
        }

        void create(String[] expectedServices) {
            if (TRACE) {
                trace("Create instrument %s class %s %n", instrument, instrumentClass);
            }
            try {
                services = env.onCreate(instrument);
                if (expectedServices != null && !TruffleOptions.AOT) {
                    checkServices(expectedServices);
                }
            } catch (Throwable e) {
                failInstrumentInitialization(env, String.format("Failed calling onCreate of instrument class %s", instrumentClass.getName()), e);
                return;
            }
            if (TRACE) {
                trace("Created instrument %s class %s %n", instrument, instrumentClass);
            }
        }

        private boolean checkServices(String[] expectedServices) {
            LOOP: for (String name : expectedServices) {
                for (Object obj : services) {
                    if (findType(name, obj.getClass())) {
                        continue LOOP;
                    }
                }
                failInstrumentInitialization(env, String.format("%s declares service %s but doesn't register it", instrumentClass.getName(), name), null);
            }
            return true;
        }

        private boolean findType(String name, Class<?> type) {
            if (type == null) {
                return false;
            }
            if (type.getName().equals(name) || (type.getCanonicalName() != null && type.getCanonicalName().equals(name))) {
                return true;
            }
            if (findType(name, type.getSuperclass())) {
                return true;
            }
            for (Class<?> inter : type.getInterfaces()) {
                if (findType(name, inter)) {
                    return true;
                }
            }
            return false;
        }

        boolean isInitialized() {
            return instrument != null;
        }

        TruffleInstrument getInstrument() {
            return instrument;
        }

        @Override
        public <T extends ContextsListener> EventBinding<T> attachContextsListener(T listener, boolean includeActiveContexts) {
            return InstrumentationHandler.this.attachContextsListener(this, listener, includeActiveContexts);
        }

        @Override
        public <T extends ThreadsListener> EventBinding<T> attachThreadsListener(T listener, boolean includeStartedThreads) {
            return InstrumentationHandler.this.attachThreadsListener(this, listener, includeStartedThreads);
        }

        @Override
        void dispose() {
            instrument.onDispose(env);
        }

        @Override
        <T> T lookup(InstrumentationHandler handler, Class<T> type) {
            if (services != null) {
                for (Object service : services) {
                    if (type.isInstance(service)) {
                        return type.cast(service);
                    }
                }
            }
            return null;
        }

    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleLanguage language
     * implementations}.
     */
    final class EngineInstrumenter extends AbstractInstrumenter {

        @Override
        void dispose() {
        }

        @Override
        <T> T lookup(InstrumentationHandler handler, Class<T> type) {
            return null;
        }

        @Override
        boolean isInstrumentableRoot(RootNode rootNode) {
            return true;
        }

        @Override
        boolean isInstrumentableSource(Source source) {
            return true;
        }

        @Override
        void verifyFilter(SourceSectionFilter filter) {
        }

        @Override
        public Set<Class<?>> queryTags(Node node) {
            return queryTagsImpl(node, null);
        }

        @Override
        public <T extends ContextsListener> EventBinding<T> attachContextsListener(T listener, boolean includeActiveContexts) {
            throw new UnsupportedOperationException("Not supported in engine instrumenter.");
        }

        @Override
        public <T extends ThreadsListener> EventBinding<T> attachThreadsListener(T listener, boolean includeStartedThreads) {
            throw new UnsupportedOperationException("Not supported in engine instrumenter.");
        }

    }

    /**
     * Provider of instrumentation services for {@linkplain TruffleLanguage language
     * implementations}.
     */
    final class LanguageClientInstrumenter<T> extends AbstractInstrumenter {

        private final LanguageInfo languageInfo;

        LanguageClientInstrumenter(LanguageInfo info) {
            this.languageInfo = info;
        }

        @Override
        boolean isInstrumentableSource(Source source) {
            String mimeType = source.getMimeType();
            if (mimeType == null) {
                return false;
            }
            return languageInfo.getMimeTypes().contains(mimeType);
        }

        @Override
        boolean isInstrumentableRoot(RootNode node) {
            LanguageInfo langInfo = node.getLanguageInfo();
            if (langInfo == null) {
                return false;
            }
            if (langInfo != languageInfo) {
                return false;
            }
            return true;
        }

        @Override
        public Set<Class<?>> queryTags(Node node) {
            return queryTagsImpl(node, languageInfo);
        }

        @Override
        void verifyFilter(SourceSectionFilter filter) {
            Set<Class<?>> providedTags = getProvidedTags(languageInfo);
            // filters must not reference tags not declared in @RequiredTags
            Set<Class<?>> referencedTags = filter.getReferencedTags();
            if (!providedTags.containsAll(referencedTags)) {
                Set<Class<?>> missingTags = new HashSet<>(referencedTags);
                missingTags.removeAll(providedTags);
                Set<Class<?>> allTags = new LinkedHashSet<>(providedTags);
                allTags.addAll(missingTags);
                StringBuilder builder = new StringBuilder("{");
                String sep = "";
                for (Class<?> tag : allTags) {
                    builder.append(sep);
                    builder.append(tag.getSimpleName());
                    sep = ", ";
                }
                builder.append("}");
                Nodes langAccess = AccessorInstrumentHandler.nodesAccess();
                TruffleLanguage<?> lang = langAccess.getLanguageSpi(languageInfo);
                throw new IllegalArgumentException(String.format("The attached filter %s references the following tags %s which are not declared as provided by the language. " +
                                "To fix this annotate the language class %s with @%s(%s).",
                                filter, missingTags, lang.getClass().getName(), ProvidedTags.class.getSimpleName(), builder));
            }
        }

        @Override
        public <S extends ContextsListener> EventBinding<S> attachContextsListener(S listener, boolean includeActiveContexts) {
            throw new UnsupportedOperationException("Not supported in language instrumenter.");
        }

        @Override
        public <S extends ThreadsListener> EventBinding<S> attachThreadsListener(S listener, boolean includeStartedThreads) {
            throw new UnsupportedOperationException("Not supported in language instrumenter.");
        }

        @Override
        void dispose() {
            // nothing to do
        }

        @Override
        <S> S lookup(InstrumentationHandler handler, Class<S> type) {
            return null;
        }

    }

    /**
     * Shared implementation of instrumentation services for clients whose requirements and
     * privileges may vary.
     */
    abstract class AbstractInstrumenter extends Instrumenter {

        abstract void dispose();

        abstract <T> T lookup(InstrumentationHandler handler, Class<T> type);

        public void disposeBinding(EventBinding<?> binding) {
            InstrumentationHandler.this.disposeBinding(binding);
        }

        abstract boolean isInstrumentableRoot(RootNode rootNode);

        abstract boolean isInstrumentableSource(Source source);

        final Set<Class<?>> queryTagsImpl(Node node, LanguageInfo onlyLanguage) {
            SourceSection sourceSection = node.getSourceSection();
            if (!InstrumentationHandler.isInstrumentableNode(node, sourceSection)) {
                return Collections.emptySet();
            }

            RootNode root = node.getRootNode();
            if (root == null) {
                return Collections.emptySet();
            }

            if (onlyLanguage != null && root.getLanguageInfo() != onlyLanguage) {
                throw new IllegalArgumentException("The language instrumenter cannot query tags of nodes of other languages.");
            }
            Set<Class<?>> providedTags = getProvidedTags(root);
            if (providedTags.isEmpty()) {
                return Collections.emptySet();
            }

            Set<Class<?>> tags = new HashSet<>();
            for (Class<?> providedTag : providedTags) {
                if (hasTagImpl(providedTags, node, providedTag)) {
                    tags.add(providedTag);
                }
            }
            return Collections.unmodifiableSet(tags);
        }

        @Override
        public final <T extends ExecutionEventNodeFactory> EventBinding<T> attachFactory(SourceSectionFilter filter, T factory) {
            verifyFilter(filter);
            return InstrumentationHandler.this.attachFactory(this, filter, factory);
        }

        @Override
        public final <T extends ExecutionEventListener> EventBinding<T> attachListener(SourceSectionFilter filter, T listener) {
            verifyFilter(filter);
            return InstrumentationHandler.this.attachListener(this, filter, listener);
        }

        @Override
        public <T extends LoadSourceListener> EventBinding<T> attachLoadSourceListener(SourceSectionFilter filter, T listener, boolean notifyLoaded) {
            verifySourceOnly(filter);
            verifyFilter(filter);
            return InstrumentationHandler.this.attachSourceListener(this, filter, listener, notifyLoaded);
        }

        @Override
        public <T extends LoadSourceSectionListener> EventBinding<T> attachLoadSourceSectionListener(SourceSectionFilter filter, T listener, boolean notifyLoaded) {
            verifyFilter(filter);
            return InstrumentationHandler.this.attachSourceSectionListener(this, filter, listener, notifyLoaded);
        }

        @Override
        public <T extends AllocationListener> EventBinding<T> attachAllocationListener(AllocationEventFilter filter, T listener) {
            return InstrumentationHandler.this.attachAllocationListener(this, filter, listener);
        }

        @Override
        public <T extends OutputStream> EventBinding<T> attachOutConsumer(T stream) {
            return InstrumentationHandler.this.attachOutputConsumer(this, stream, false);
        }

        @Override
        public <T extends OutputStream> EventBinding<T> attachErrConsumer(T stream) {
            return InstrumentationHandler.this.attachOutputConsumer(this, stream, true);
        }

        private void verifySourceOnly(SourceSectionFilter filter) {
            if (!filter.isSourceOnly()) {
                throw new IllegalArgumentException(String.format("The attached filter %s uses filters that require source sections to verifiy. " +
                                "Source listeners can only use filter critera based on Source objects like mimeTypeIs or sourceIs.", filter));
            }
        }

        abstract void verifyFilter(SourceSectionFilter filter);

    }

    /**
     * A list collection data structure that is optimized for fast non-blocking traversals. There is
     * adds and no explicit removal. Removals are based on a side effect of the element, by
     * returning <code>null</code> in {@link AbstractAsyncCollection#unwrap(Object)}. It is not
     * possible to reliably query the {@link AbstractAsyncCollection#size()} of the collection,
     * therefore it throws an {@link UnsupportedOperationException}.
     */
    private abstract static class AbstractAsyncCollection<T, R> extends AbstractCollection<R> {
        /*
         * We use an atomic reference list as we don't want to see holes in the array when appending
         * to it. This allows us to use null as a safe terminator for the array.
         */
        private volatile AtomicReferenceArray<T> values;

        /*
         * Size can be non volatile as it is not exposed or used for traversal.
         */
        private int nextInsertionIndex;

        AbstractAsyncCollection(int initialCapacity) {
            if (initialCapacity <= 0) {
                throw new IllegalArgumentException("Invalid initial capacity " + initialCapacity);
            }
            this.values = new AtomicReferenceArray<>(initialCapacity);
        }

        @Override
        public final synchronized boolean add(R reference) {
            T wrappedElement = wrap(reference);
            if (wrappedElement == null) {
                // fail early
                throw new NullPointerException();
            }
            if (nextInsertionIndex >= values.length()) {
                compact();
            }
            values.set(nextInsertionIndex++, wrappedElement);
            return true;
        }

        @Override
        public int size() {
            // size cannot be supported reliably
            throw new UnsupportedOperationException();
        }

        @Override
        public final boolean isEmpty() {
            return values.get(0) == null;
        }

        protected abstract T wrap(R element);

        protected abstract R unwrap(T element);

        private void compact() {
            AtomicReferenceArray<T> localValues = values;
            int liveElements = 0;
            /*
             * We count the still alive elements.
             */
            for (int i = 0; i < localValues.length(); i++) {
                T ref = localValues.get(i);
                if (ref == null) {
                    break;
                }
                if (unwrap(ref) != null) {
                    liveElements++;
                }
            }

            /*
             * We ensure that the capacity after compaction is always twice as big as the number of
             * live elements. This can make the array grow or shrink as needed.
             */
            AtomicReferenceArray<T> newValues = new AtomicReferenceArray<>(Math.max(liveElements * 2, 8));
            int index = 0;
            for (int i = 0; i < localValues.length(); i++) {
                T ref = localValues.get(i);
                if (ref == null) {
                    break;
                }
                if (unwrap(ref) != null) {
                    newValues.set(index++, ref);
                }
            }

            this.nextInsertionIndex = index;
            this.values = newValues;
        }

        /**
         * Returns an iterator which can be traversed without a lock. The iterator that is
         * constructed is not sequentially consistent. In other words, the user of the iterator may
         * observe values that were added after the iterator was created.
         */
        @Override
        public Iterator<R> iterator() {
            return new Iterator<R>() {

                /*
                 * We need to capture the values field in the iterator to have a consistent view on
                 * the data while iterating.
                 */
                private final AtomicReferenceArray<T> values = AbstractAsyncCollection.this.values;
                private int index;
                private R queuedNext;

                public boolean hasNext() {
                    R next = queuedNext;
                    if (next == null) {
                        next = queueNext();
                        queuedNext = next;
                    }
                    return next != null;
                }

                private R queueNext() {
                    int localIndex = index;
                    AtomicReferenceArray<T> array = values;
                    while (true) {
                        if (localIndex >= array.length()) {
                            return null;
                        }
                        T localValue = array.get(localIndex);
                        if (localValue == null) {
                            return null;
                        }
                        localIndex++;
                        R alive = unwrap(localValue);
                        if (alive == null) {
                            continue;
                        }
                        index = localIndex;
                        return alive;
                    }
                }

                public R next() {
                    R next = queuedNext;
                    if (next == null) {
                        next = queueNext();
                        if (next == null) {
                            throw new NoSuchElementException();
                        }
                    }
                    queuedNext = null;
                    return next;
                }

                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

    }

    /**
     * An async list implementation that removes elements whenever a binding was disposed.
     */
    private static final class EventBindingList<EB extends EventBinding<?>> extends AbstractAsyncCollection<EB, EB> {

        EventBindingList(int initialCapacity) {
            super(initialCapacity);
        }

        @Override
        protected EB wrap(EB element) {
            return element;
        }

        @Override
        protected EB unwrap(EB element) {
            if (element.isDisposed()) {
                return null;
            }
            return element;
        }

    }

    /**
     * An async list using weak references.
     */
    private static final class WeakAsyncList<T> extends AbstractAsyncCollection<WeakReference<T>, T> {

        WeakAsyncList(int initialCapacity) {
            super(initialCapacity);
        }

        @Override
        protected WeakReference<T> wrap(T element) {
            return new WeakReference<>(element);
        }

        @Override
        protected T unwrap(WeakReference<T> element) {
            return element.get();
        }

    }

    static final AccessorInstrumentHandler ACCESSOR = new AccessorInstrumentHandler();

    static final class AccessorInstrumentHandler extends Accessor {

        static Accessor.Nodes nodesAccess() {
            return ACCESSOR.nodes();
        }

        static Accessor.LanguageSupport langAccess() {
            return ACCESSOR.languageSupport();
        }

        static Accessor.EngineSupport engineAccess() {
            return ACCESSOR.engineSupport();
        }

        static Accessor.InteropSupport interopAccess() {
            return ACCESSOR.interopSupport();
        }

        @Override
        protected InstrumentSupport instrumentSupport() {
            return new InstrumentImpl();
        }

        protected boolean isTruffleObject(Object value) {
            return interopSupport().isTruffleObject(value);
        }

        static final class InstrumentImpl extends InstrumentSupport {

            @Override
            public Object createInstrumentationHandler(Object vm, DispatchOutputStream out, DispatchOutputStream err, InputStream in) {
                return new InstrumentationHandler(vm, out, err, in);
            }

            @Override
            public void initializeInstrument(Object instrumentationHandler, Object key, Class<?> instrumentClass) {
                ((InstrumentationHandler) instrumentationHandler).initializeInstrument(key, instrumentClass);
            }

            @Override
            public void createInstrument(Object instrumentationHandler, Object key, String[] expectedServices, OptionValues options) {
                ((InstrumentationHandler) instrumentationHandler).createInstrument(key, expectedServices, options);
            }

            @Override
            public Object getEngineInstrumenter(Object instrumentationHandler) {
                return ((InstrumentationHandler) instrumentationHandler).engineInstrumenter;
            }

            @Override
            public void onNodeInserted(RootNode rootNode, Node tree) {
                InstrumentationHandler handler = getHandler(rootNode);
                if (handler != null) {
                    handler.onNodeInserted(rootNode, tree);
                }
            }

            @Override
            public OptionDescriptors describeOptions(Object instrumentationHandler, Object key, String requiredGroup) {
                InstrumentClientInstrumenter instrumenter = (InstrumentClientInstrumenter) ((InstrumentationHandler) instrumentationHandler).instrumenterMap.get(key);
                OptionDescriptors descriptors = instrumenter.instrument.getOptionDescriptors();
                if (descriptors == null) {
                    descriptors = OptionDescriptors.EMPTY;
                }
                String groupPlusDot = requiredGroup + ".";
                for (OptionDescriptor descriptor : descriptors) {
                    if (!descriptor.getName().equals(requiredGroup) && !descriptor.getName().startsWith(groupPlusDot)) {
                        throw new IllegalArgumentException(String.format("Illegal option prefix in name '%s' specified for option described by instrument '%s'. " +
                                        "The option prefix must match the id of the instrument '%s'.",
                                        descriptor.getName(), instrumenter.instrument.getClass().getName(), requiredGroup));
                    }
                }
                return descriptors;
            }

            @Override
            public void disposeInstrument(Object instrumentationHandler, Object key, boolean cleanupRequired) {
                ((InstrumentationHandler) instrumentationHandler).disposeInstrumenter(key, cleanupRequired);
            }

            @Override
            public void collectEnvServices(Set<Object> collectTo, Object languageShared, LanguageInfo info) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(languageShared);
                Instrumenter instrumenter = instrumentationHandler.forLanguage(info);
                collectTo.add(instrumenter);
                AllocationReporter allocationReporter = instrumentationHandler.getAllocationReporter(info);
                collectTo.add(allocationReporter);
            }

            @Override
            public <T> T getInstrumentationHandlerService(Object vm, Object key, Class<T> type) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) vm;
                return instrumentationHandler.lookup(key, type);
            }

            @Override
            public void onFirstExecution(RootNode rootNode) {
                InstrumentationHandler handler = getHandler(rootNode);
                if (handler != null) {
                    handler.onFirstExecution(rootNode);
                }
            }

            @Override
            public void onLoad(RootNode rootNode) {
                InstrumentationHandler handler = getHandler(rootNode);
                if (handler != null) {
                    handler.onLoad(rootNode);
                }
            }

            @Override
            public Iterable<Scope> findTopScopes(TruffleLanguage.Env env) {
                return TruffleInstrument.Env.findTopScopes(env);
            }

            @Override
            @CompilerDirectives.TruffleBoundary
            public void notifyContextCreated(Object engine, TruffleContext context) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyContextCreated(context);
            }

            @Override
            @CompilerDirectives.TruffleBoundary
            public void notifyContextClosed(Object engine, TruffleContext context) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyContextClosed(context);
            }

            @Override
            public void notifyLanguageContextCreated(Object engine, TruffleContext context, LanguageInfo info) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyLanguageContextCreated(context, info);
            }

            @Override
            public void notifyLanguageContextInitialized(Object engine, TruffleContext context, LanguageInfo info) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyLanguageContextInitialized(context, info);
            }

            @Override
            public void notifyLanguageContextFinalized(Object engine, TruffleContext context, LanguageInfo info) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyLanguageContextFinalized(context, info);
            }

            @Override
            public void notifyLanguageContextDisposed(Object engine, TruffleContext context, LanguageInfo info) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyLanguageContextDisposed(context, info);
            }

            @Override
            @CompilerDirectives.TruffleBoundary
            public void notifyThreadStarted(Object engine, TruffleContext context, Thread thread) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyThreadStarted(context, thread);
            }

            @Override
            @CompilerDirectives.TruffleBoundary
            public void notifyThreadFinished(Object engine, TruffleContext context, Thread thread) {
                InstrumentationHandler instrumentationHandler = (InstrumentationHandler) engineAccess().getInstrumentationHandler(engine);
                instrumentationHandler.notifyThreadFinished(context, thread);
            }

            private static InstrumentationHandler getHandler(RootNode rootNode) {
                LanguageInfo info = rootNode.getLanguageInfo();
                if (info == null) {
                    return null;
                }
                Object languageShared = nodesAccess().getEngineObject(info);
                if (languageShared == null) {
                    return null;
                }
                return (InstrumentationHandler) engineAccess().getInstrumentationHandler(languageShared);
            }

        }
    }

}
