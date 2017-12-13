/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.svm.core.graal.code.amd64;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;
import static com.oracle.svm.core.util.VMError.unimplemented;
import static jdk.vm.ci.amd64.AMD64.rax;
import static jdk.vm.ci.amd64.AMD64.rbx;
import static jdk.vm.ci.amd64.AMD64.rcx;
import static jdk.vm.ci.amd64.AMD64.rdi;
import static jdk.vm.ci.amd64.AMD64.rdx;
import static jdk.vm.ci.amd64.AMD64.rsi;
import static jdk.vm.ci.amd64.AMD64.rbp;
import static jdk.vm.ci.amd64.AMD64.rsp;
import static jdk.vm.ci.amd64.AMD64.r8;
import static jdk.vm.ci.amd64.AMD64.r9;
import static jdk.vm.ci.amd64.AMD64.r12;
import static jdk.vm.ci.amd64.AMD64.r13;
import static jdk.vm.ci.amd64.AMD64.r14;
import static jdk.vm.ci.amd64.AMD64.r15;
import static jdk.vm.ci.amd64.AMD64.xmm0;
import static jdk.vm.ci.amd64.AMD64.xmm1;
import static jdk.vm.ci.amd64.AMD64.xmm2;
import static jdk.vm.ci.amd64.AMD64.xmm3;
import static jdk.vm.ci.amd64.AMD64.xmm4;
import static jdk.vm.ci.amd64.AMD64.xmm5;
import static jdk.vm.ci.amd64.AMD64.xmm6;
import static jdk.vm.ci.amd64.AMD64.xmm7;
import static jdk.vm.ci.amd64.AMD64.valueRegistersSSE;

import java.util.ArrayList;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.graal.meta.SubstrateRegisterConfig;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CallingConvention.Type;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterArray;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class SubstrateAMD64RegisterConfig implements SubstrateRegisterConfig {

    public enum ConfigKind {
        NORMAL,
        NATIVE_TO_JAVA,
    }

    private final TargetDescription target;
    private final RegisterArray generalParameterRegs;
    private final RegisterArray xmmParameterRegs;
    private final RegisterArray allocatableRegs;
    private final RegisterArray calleeSaveRegisters;
    private final RegisterAttributes[] attributesMap;
    private final MetaAccessProvider metaAccess;
    private final Register threadRegister;
    private final Register heapBaseRegister;

    public SubstrateAMD64RegisterConfig(ConfigKind config, MetaAccessProvider metaAccess, TargetDescription target) {
        this.target = target;
        this.metaAccess = metaAccess;

        // This is the Linux 64-bit ABI for parameters.
        generalParameterRegs = new RegisterArray(rdi, rsi, rdx, rcx, r8, r9);
        xmmParameterRegs = new RegisterArray(xmm0, xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7);

        heapBaseRegister = SubstrateOptions.UseHeapBaseRegister.getValue() ? r14 : null;
        threadRegister = SubstrateOptions.MultiThreaded.getValue() ? r15 : null;

        ArrayList<Register> regs = new ArrayList<>(valueRegistersSSE.asList());
        regs.remove(rsp);
        regs.remove(rbp);
        regs.remove(heapBaseRegister);
        regs.remove(threadRegister);
        allocatableRegs = new RegisterArray(regs);

        switch (config) {
            case NORMAL:
                calleeSaveRegisters = new RegisterArray();
                break;

            case NATIVE_TO_JAVA:
                /*
                 * rbp must be last in the list, so that it gets the location closest to the saved
                 * return address.
                 */
                calleeSaveRegisters = new RegisterArray(rbx, r12, r13, r14, r15, rbp);
                break;

            default:
                throw shouldNotReachHere();

        }
        attributesMap = RegisterAttributes.createMap(this, AMD64.allRegisters);
    }

    @Override
    public Register getReturnRegister(JavaKind kind) {
        switch (kind) {
            case Boolean:
            case Byte:
            case Char:
            case Short:
            case Int:
            case Long:
            case Object:
                return rax;
            case Float:
            case Double:
                return xmm0;
            case Void:
                return null;
            default:
                throw shouldNotReachHere();
        }
    }

    @Override
    public Register getFrameRegister() {
        return rsp;
    }

    @Override
    public Register getThreadRegister() {
        return threadRegister;
    }

    @Override
    public Register getHeapBaseRegister() {
        return heapBaseRegister;
    }

    @Override
    public RegisterArray getAllocatableRegisters() {
        return allocatableRegs;
    }

    @Override
    public RegisterArray getCalleeSaveRegisters() {
        return calleeSaveRegisters;
    }

    @Override
    public RegisterArray getCallerSaveRegisters() {
        return getAllocatableRegisters();
    }

    @Override
    public boolean areAllAllocatableRegistersCallerSaved() {
        return true;
    }

    @Override
    public RegisterAttributes[] getAttributesMap() {
        return attributesMap;
    }

    @Override
    public RegisterArray getCallingConventionRegisters(Type type, JavaKind kind) {
        throw unimplemented();
        // return null;
    }

    @Override
    public CallingConvention getCallingConvention(Type t, JavaType returnType, JavaType[] parameterTypes, ValueKindFactory<?> valueKindFactory) {
        SubstrateCallingConventionType type = (SubstrateCallingConventionType) t;
        boolean isEntryPoint = type.nativeABI && !type.outgoing;

        AllocatableValue[] locations = new AllocatableValue[parameterTypes.length];

        int currentGeneral = 0;
        int currentXMM = 0;

        /*
         * We have to reserve a slot between return address and outgoing parameters for the deopt
         * frame handle. Exception: calls to native methods.
         */
        int currentStackOffset = (type.nativeABI ? 0 : target.wordSize);

        JavaKind[] kinds = new JavaKind[locations.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            JavaKind kind = ObjectLayout.getCallSignatureKind(isEntryPoint, (ResolvedJavaType) parameterTypes[i], metaAccess, target);
            kinds[i] = kind;

            switch (kind) {
                case Byte:
                case Boolean:
                case Short:
                case Char:
                case Int:
                case Long:
                case Object:
                    if (currentGeneral < generalParameterRegs.size()) {
                        Register register = generalParameterRegs.get(currentGeneral++);
                        locations[i] = register.asValue(valueKindFactory.getValueKind(kind.getStackKind()));
                    }
                    break;
                case Float:
                case Double:
                    if (currentXMM < xmmParameterRegs.size()) {
                        Register register = xmmParameterRegs.get(currentXMM++);
                        locations[i] = register.asValue(valueKindFactory.getValueKind(kind));
                    }
                    break;
                default:
                    throw shouldNotReachHere();
            }

            if (locations[i] == null) {
                ValueKind<?> valueKind = valueKindFactory.getValueKind(kind.getStackKind());
                locations[i] = StackSlot.get(valueKind, currentStackOffset, !type.outgoing);
                currentStackOffset += Math.max(valueKind.getPlatformKind().getSizeInBytes(), target.wordSize);
            }
        }

        JavaKind returnKind = returnType == null ? JavaKind.Void : ObjectLayout.getCallSignatureKind(isEntryPoint, (ResolvedJavaType) returnType, metaAccess, target);
        AllocatableValue returnLocation = returnKind == JavaKind.Void ? Value.ILLEGAL : getReturnRegister(returnKind).asValue(valueKindFactory.getValueKind(returnKind.getStackKind()));
        SubstrateCallingConvention callingConvention = new SubstrateCallingConvention(currentStackOffset, returnLocation, locations);
        callingConvention.setArgumentStorageKinds(kinds);
        return callingConvention;
    }

    @Override
    public RegisterArray filterAllocatableRegisters(PlatformKind kind, RegisterArray registers) {
        ArrayList<Register> list = new ArrayList<>();
        for (Register reg : registers) {
            if (target.arch.canStoreValue(reg.getRegisterCategory(), kind)) {
                list.add(reg);
            }
        }

        return new RegisterArray(list);
    }
}
