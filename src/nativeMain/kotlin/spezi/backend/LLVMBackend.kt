package spezi.backend

import kotlinx.cinterop.*
import llvm.*
import spezi.common.CompilerException
import spezi.common.Context
import spezi.common.Disposable
import spezi.domain.*

class LLVMBackend(private val ctx: Context) : Disposable {
    private val context = LLVMContextCreate()
    private val module = LLVMModuleCreateWithNameInContext("spezi_module", context)
    private val builder = LLVMCreateBuilderInContext(context)

    private val symbols = ScopedSymbolTable()
    private val structs = mutableMapOf<String, StructDef>()
    private val externNames = mutableSetOf<String>()

    init {
        LLVMInitializeNativeTarget()
        LLVMInitializeNativeAsmPrinter()
    }

    override fun dispose() {
        LLVMDisposeBuilder(builder)
        LLVMDisposeModule(module)
        LLVMContextDispose(context)
    }

    private fun mapType(t: Type): LLVMTypeRef = when (t) {
        Type.I32 -> LLVMInt32TypeInContext(context)!!
        Type.Bool -> LLVMInt1TypeInContext(context)!!
        Type.String -> LLVMPointerType(LLVMInt8TypeInContext(context), 0u)!!
        Type.Void -> LLVMVoidTypeInContext(context)!!
        is Type.Struct -> LLVMGetTypeByName(module, t.name) ?: throw CompilerException("Codegen: Unknown struct ${t.name}")

        Type.Unknown, Type.Error -> LLVMInt32TypeInContext(context)!!
    }

    private fun getMangledName(name: String, argTypes: List<Type>, extensionOf: Type? = null): String {
        if (externNames.contains(name) || name == "main") return name
        val base = if (extensionOf != null) "${extensionOf.name}_$name" else name
        if (argTypes.isEmpty()) return base
        val suffix = argTypes.joinToString("_") { it.name }
        return "${base}_$suffix"
    }

    fun generate(p: Program) {
        if (ctx.options.verbose) ctx.reporter.info("Starting LLVM Codegen...")

        p.elements.filterIsInstance<StructDef>().forEach { s ->
            structs[s.name] = s
            LLVMStructCreateNamed(context, s.name)
        }

        p.elements.filterIsInstance<StructDef>().forEach { s ->
            val llvmType = LLVMGetTypeByName(module, s.name)
            val fieldTypes = s.fields.map { mapType(it.second) }.toCValues()
            LLVMStructSetBody(llvmType, fieldTypes, s.fields.size.toUInt(), 0)
        }

        p.elements.filterIsInstance<ExternFnDef>().forEach { f ->
            externNames.add(f.name)
            val args = f.args.map { mapType(it.second) }.toCValues()
            val ft = LLVMFunctionType(mapType(f.retType), args, f.args.size.toUInt(), 0)
            LLVMAddFunction(module, f.name, ft)
        }

        p.elements.filterIsInstance<FnDef>().forEach { genFn(it) }
    }

    private fun genFn(fn: FnDef) {
        val mangledName = getMangledName(fn.name, fn.args.map { it.second }, fn.extensionOf)

        val argTypes = mutableListOf<LLVMTypeRef>()
        fn.extensionOf?.let { argTypes.add(LLVMPointerType(mapType(it), 0u)!!) }
        fn.args.forEach { argTypes.add(mapType(it.second)) }

        val ft = LLVMFunctionType(mapType(fn.retType), argTypes.toCValues(), argTypes.size.toUInt(), 0)
        val fVal = LLVMAddFunction(module, mangledName, ft)!!

        val entryBB = LLVMAppendBasicBlock(fVal, "entry")
        LLVMPositionBuilderAtEnd(builder, entryBB)

        symbols.enterScope()
        var idx = 0u

        if (fn.extensionOf != null) {
            val ptr = LLVMGetParam(fVal, idx++)
            val ptrType = LLVMPointerType(mapType(fn.extensionOf), 0u)!!
            val alloca = LLVMBuildAlloca(builder, ptrType, "self.addr")!!
            LLVMBuildStore(builder, ptr, alloca)
            symbols.define("self", alloca, ptrType, false)
        }

        fn.args.forEach { (name, type) ->
            val value = LLVMGetParam(fVal, idx++)
            val t = mapType(type)
            val alloca = LLVMBuildAlloca(builder, t, name)!!
            LLVMBuildStore(builder, value, alloca)
            symbols.define(name, alloca, t, false)
        }

        genBlock(fn.body)

        if (fn.retType == Type.Void && LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null) {
            LLVMBuildRetVoid(builder)
        }

        symbols.exitScope()
    }

    private fun genBlock(b: Block) {
        b.stmts.forEach { genStmt(it) }
    }

    private fun genStmt(s: AstNode) {
        when (s) {
            is VarDecl -> {
                val type = mapType(s.type ?: s.init!!.resolvedType)
                val alloca = LLVMBuildAlloca(builder, type, s.name)!!

                if (s.init != null) {
                    LLVMBuildStore(builder, genExpr(s.init), alloca)
                }
                symbols.define(s.name, alloca, type, s.isMut)
            }
            is Assign -> {
                val info = symbols.lookup(s.name) ?: throw CompilerException("Backend: Undefined var ${s.name}")

                LLVMBuildStore(builder, genExpr(s.value), info.alloca)
            }
            is ReturnStmt -> {
                if (s.value != null) LLVMBuildRet(builder, genExpr(s.value))

                else LLVMBuildRetVoid(builder)
            }
            is IfStmt -> {
                val cond = LLVMBuildICmp(builder, LLVMIntNE, genExpr(s.cond), LLVMConstInt(mapType(Type.Bool), 0u, 0), "cond")
                val func = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
                val thenBB = LLVMAppendBasicBlock(func, "then")
                val elseBB = LLVMAppendBasicBlock(func, "else")
                val mergeBB = LLVMAppendBasicBlock(func, "merge")

                LLVMBuildCondBr(builder, cond, thenBB, elseBB)

                LLVMPositionBuilderAtEnd(builder, thenBB)
                symbols.enterScope()
                genBlock(s.thenBlock)
                symbols.exitScope()
                if (LLVMGetBasicBlockTerminator(thenBB) == null) LLVMBuildBr(builder, mergeBB)

                LLVMPositionBuilderAtEnd(builder, elseBB)
                s.elseBlock?.let {
                    symbols.enterScope()
                    genBlock(it)
                    symbols.exitScope()
                }
                if (LLVMGetBasicBlockTerminator(elseBB) == null) LLVMBuildBr(builder, mergeBB)

                LLVMPositionBuilderAtEnd(builder, mergeBB)
            }
            is Expr -> genExpr(s)
            else -> {}
        }
    }

    private fun genExpr(e: Expr): LLVMValueRef = when (e) {
        is LiteralInt -> LLVMConstInt(mapType(Type.I32), e.value.toULong(), 0)!!
        is LiteralBool -> LLVMConstInt(mapType(Type.Bool), if (e.value) 1uL else 0uL, 0)!!
        is LiteralString -> LLVMBuildGlobalStringPtr(builder, e.value, ".str")!!
        is VarRef -> {
            val info = symbols.lookup(e.name)!!
            LLVMBuildLoad2(builder, info.type, info.alloca, e.name)!!
        }
        is BinOp -> {
            val l = genExpr(e.left)
            val r = genExpr(e.right)
            when (e.op) {
                TokenType.PLUS -> LLVMBuildAdd(builder, l, r, "tmp_add")!!
                TokenType.MINUS -> LLVMBuildSub(builder, l, r, "tmp_sub")!!
                TokenType.STAR -> LLVMBuildMul(builder, l, r, "tmp_mul")!!
                TokenType.EQEQ -> LLVMBuildZExt(builder, LLVMBuildICmp(builder, LLVMIntEQ, l, r, "tmp_eq"), mapType(Type.Bool), "zext")!!
                TokenType.NEQ -> LLVMBuildZExt(builder, LLVMBuildICmp(builder, LLVMIntNE, l, r, "tmp_neq"), mapType(Type.Bool), "zext")!!
                else -> throw CompilerException("Backend: Op ${e.op} not implemented")
            }
        }
        is ConstructorCall -> {
            val stType = mapType(Type.Struct(e.typeName))
            val alloca = LLVMBuildAlloca(builder, stType, "new_st")!!
            e.args.forEachIndexed { i, arg ->
                val ptr = LLVMBuildStructGEP2(builder, stType, alloca, i.toUInt(), "gep")
                LLVMBuildStore(builder, genExpr(arg), ptr)
            }
            LLVMBuildLoad2(builder, stType, alloca, "st_val")!!
        }
        is Call -> handleCall(e)
        is Access -> {
            val obj = e.objectExpr
            if (obj is VarRef) {
                val info = symbols.lookup(obj.name)!!
                val isPtr = LLVMGetTypeKind(info.type) == LLVMPointerTypeKind
                val base = if (isPtr) LLVMBuildLoad2(builder, info.type, info.alloca, "load_ptr")!! else info.alloca

                val stName = (obj.resolvedType as Type.Struct).name
                val stDef = structs[stName]!!
                val idx = stDef.fields.indexOfFirst { it.first == e.member }

                val stType = LLVMGetTypeByName(module, stName)!!
                val ptr = LLVMBuildStructGEP2(builder, stType, base, idx.toUInt(), "fgep")
                LLVMBuildLoad2(builder, mapType(stDef.fields[idx].second), ptr, "fload")!!
            } else throw CompilerException("Backend: Access only supported on variables")
        }
        else -> throw CompilerException("Backend: Unknown expr $e")
    }

    private fun handleCall(e: Call): LLVMValueRef = memScoped {
        var fnName = getMangledName(e.name, e.args.map { it.resolvedType })
        var fn = LLVMGetNamedFunction(module, fnName)
        var isExtension = false

        if (fn == null && e.args.isNotEmpty()) {
            val firstT = e.args[0].resolvedType
            if (firstT is Type.Struct) {
                val suffixArgs = e.args.drop(1).map { it.resolvedType }
                fnName = getMangledName(e.name, suffixArgs, firstT)
                fn = LLVMGetNamedFunction(module, fnName)
                if (fn != null) isExtension = true
            }
        }

        if (fn == null) throw CompilerException("Backend: Function '${e.name}' not found.")

        val args = e.args.mapIndexed { i, arg ->
            if (isExtension && i == 0) {
                if (arg is VarRef) {
                    val info = symbols.lookup(arg.name)!!
                    if (LLVMGetTypeKind(info.type) == LLVMPointerTypeKind) {
                        LLVMBuildLoad2(builder, info.type, info.alloca, "load_ptr")!!
                    } else info.alloca
                } else {
                    val v = genExpr(arg)
                    val tmp = LLVMBuildAlloca(builder, LLVMTypeOf(v), "spill")!!
                    LLVMBuildStore(builder, v, tmp)
                    tmp
                }
            } else genExpr(arg)
        }

        LLVMBuildCall2(builder, LLVMGlobalGetValueType(fn), fn, args.toCValues(), args.size.toUInt(),
            if (LLVMGetTypeKind(LLVMGetReturnType(LLVMGlobalGetValueType(fn))) == LLVMVoidTypeKind) "" else "ret")!!
    }

    fun emitToFile(path: String) {
        val err = nativeHeap.alloc<CPointerVar<ByteVar>>()
        if (LLVMVerifyModule(module, LLVMVerifierFailureAction.LLVMReturnStatusAction, err.ptr) == 1) {
            val msg = err.value?.toKString() ?: "Unknown"
            throw CompilerException("LLVM Verification Failed: $msg")
        }
        LLVMPrintModuleToFile(module, path, null)
    }
}