package spezi.backend

import kotlinx.cinterop.*
import llvm.*
import spezi.common.CompilerException
import spezi.common.Context
import spezi.domain.*

class LLVMBackend(private val ctx: Context) {
    private val context = LLVMContextCreate()
    private val module = LLVMModuleCreateWithNameInContext(ctx.options.inputFile, context)
    private val builder = LLVMCreateBuilderInContext(context)

    data class VarInfo(val alloca: LLVMValueRef, val type: LLVMTypeRef, val isMut: Boolean)
    private val scopes = ArrayDeque<MutableMap<String, VarInfo>>()
    private val structs = mutableMapOf<String, StructDef>()

    init {
        LLVMInitializeNativeTarget()
        LLVMInitializeNativeAsmPrinter()
    }

    private fun mapType(t: Type): LLVMTypeRef = when (t) {
        Type.I32 -> LLVMInt32TypeInContext(context)!!
        Type.Bool -> LLVMInt1TypeInContext(context)!!
        Type.String -> LLVMPointerType(LLVMInt8TypeInContext(context), 0u)!!
        Type.Void -> LLVMVoidTypeInContext(context)!!
        is Type.Struct -> LLVMGetTypeByName(module, t.name) ?: throw CompilerException("Unknown struct ${t.name}")
        Type.Unknown, Type.Error -> LLVMInt32TypeInContext(context)!! // Should not happen in codegen if analysis passed
    }

    fun generate(p: Program) {
        if (ctx.options.verbose) ctx.reporter.info("Starting LLVM Codegen")

        p.elements.filterIsInstance<StructDef>().forEach { s -> structs[s.name]=s; LLVMStructCreateNamed(context, s.name) }
        p.elements.filterIsInstance<StructDef>().forEach { s ->
            LLVMStructSetBody(LLVMGetTypeByName(module, s.name), s.fields.map { mapType(it.second) }.toCValues(), s.fields.size.toUInt(), 0)
        }
        p.elements.filterIsInstance<ExternFnDef>().forEach { f ->
            val args = f.args.map { mapType(it.second) }.toCValues()
            LLVMAddFunction(module, f.name, LLVMFunctionType(mapType(f.retType), args, f.args.size.toUInt(), 0))
        }
        p.elements.filterIsInstance<FnDef>().forEach { genFn(it) }
    }

    private fun genFn(fn: FnDef) {
        val name = if (fn.extensionOf != null) "${fn.extensionOf.name}_${fn.name}" else fn.name
        val argsT = mutableListOf<LLVMTypeRef>()
        fn.extensionOf?.let { argsT.add(LLVMPointerType(mapType(it), 0u)!!) }
        fn.args.forEach { argsT.add(mapType(it.second)) }

        val ft = LLVMFunctionType(mapType(fn.retType), argsT.toCValues(), argsT.size.toUInt(), 0)
        val fVal = LLVMAddFunction(module, name, ft)!!
        val bb = LLVMAppendBasicBlock(fVal, "entry")
        LLVMPositionBuilderAtEnd(builder, bb)

        scopes.addFirst(mutableMapOf())
        var idx = 0u

        if (fn.extensionOf != null) {
            val ptr = LLVMGetParam(fVal, idx++)
            val ptrType = LLVMPointerType(mapType(fn.extensionOf), 0u)!!
            val alloca = LLVMBuildAlloca(builder, ptrType, "self.addr")!!
            LLVMBuildStore(builder, ptr, alloca)
            scopes.first()["self"] = VarInfo(alloca, ptrType, false)
        }

        fn.args.forEach { (n, t) ->
            val v = LLVMGetParam(fVal, idx++)
            val alloca = LLVMBuildAlloca(builder, mapType(t), n)!!
            LLVMBuildStore(builder, v, alloca)
            scopes.first()[n] = VarInfo(alloca, mapType(t), false)
        }

        genBlock(fn.body)
        if (fn.retType == Type.Void && LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null) {
            LLVMBuildRetVoid(builder)
        }
        scopes.removeFirst()
    }

    private fun genBlock(b: Block) { b.stmts.forEach { genStmt(it) } }

    private fun genStmt(s: AstNode) {
        when(s) {
            is VarDecl -> {
                val t = mapType(s.type ?: s.init!!.resolvedType)
                val alloca = LLVMBuildAlloca(builder, t, s.name)!!
                if (s.init != null) LLVMBuildStore(builder, genExpr(s.init), alloca)
                scopes.first()[s.name] = VarInfo(alloca, t, s.isMut)
            }
            is Assign -> {
                val v = scopes.firstOrNull { it.containsKey(s.name) }?.get(s.name)!!
                LLVMBuildStore(builder, genExpr(s.value), v.alloca)
            }
            is IfStmt -> {
                val cond = LLVMBuildICmp(builder, LLVMIntNE, genExpr(s.cond), LLVMConstInt(mapType(Type.Bool), 0u, 0), "cond")
                val f = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder))
                val thenB = LLVMAppendBasicBlock(f, "then"); val elseB = LLVMAppendBasicBlock(f, "else"); val mergeB = LLVMAppendBasicBlock(f, "merge")
                LLVMBuildCondBr(builder, cond, thenB, elseB)

                LLVMPositionBuilderAtEnd(builder, thenB); scopes.addFirst(mutableMapOf()); genBlock(s.thenBlock); scopes.removeFirst()
                if (LLVMGetBasicBlockTerminator(thenB) == null) LLVMBuildBr(builder, mergeB)

                LLVMPositionBuilderAtEnd(builder, elseB); s.elseBlock?.let { scopes.addFirst(mutableMapOf()); genBlock(it); scopes.removeFirst() }
                if (LLVMGetBasicBlockTerminator(elseB) == null) LLVMBuildBr(builder, mergeB)

                LLVMPositionBuilderAtEnd(builder, mergeB)
            }
            is ReturnStmt -> if (s.value != null) LLVMBuildRet(builder, genExpr(s.value)) else LLVMBuildRetVoid(builder)
            is Expr -> genExpr(s)
            else -> {}
        }
    }

    private fun genExpr(e: Expr): LLVMValueRef = when(e) {
        is LiteralInt -> LLVMConstInt(mapType(Type.I32), e.value.toULong(), 0)!!
        is LiteralString -> LLVMBuildGlobalStringPtr(builder, e.value, "str")!!
        is LiteralBool -> LLVMConstInt(mapType(Type.Bool), if(e.value) 1uL else 0uL, 0)!!
        is VarRef -> {
            val v = scopes.firstOrNull { it.containsKey(e.name) }?.get(e.name)!!
            LLVMBuildLoad2(builder, v.type, v.alloca, e.name)!!
        }
        is BinOp -> {
            val l = genExpr(e.left); val r = genExpr(e.right)
            when(e.op) {
                TokenType.PLUS -> LLVMBuildAdd(builder, l, r, "add")!!
                TokenType.MINUS -> LLVMBuildSub(builder, l, r, "sub")!!
                TokenType.STAR -> LLVMBuildMul(builder, l, r, "mul")!!
                TokenType.EQEQ -> LLVMBuildZExt(builder, LLVMBuildICmp(builder, LLVMIntEQ, l, r, "eq"), mapType(Type.Bool), "zext")!!
                else -> throw RuntimeException("Op not impl")
            }
        }
        is ConstructorCall -> {
            val stType = mapType(Type.Struct(e.typeName))
            val alloca = LLVMBuildAlloca(builder, stType, "new_st")!!
            e.args.forEachIndexed { i, arg ->
                val gep = LLVMBuildStructGEP2(builder, stType, alloca, i.toUInt(), "gep")
                LLVMBuildStore(builder, genExpr(arg), gep)
            }
            LLVMBuildLoad2(builder, stType, alloca, "st_val")!!
        }
        is Call -> handleCall(e)
        is Access -> {
            val obj = e.objectExpr
            if (obj is VarRef) {
                val v = scopes.first { it.containsKey(obj.name) }[obj.name]!!
                val isPtrToStruct = LLVMGetTypeKind(v.type) == LLVMPointerTypeKind
                val base = if(isPtrToStruct) LLVMBuildLoad2(builder, v.type, v.alloca, "load_ptr")!! else v.alloca

                val tName = (obj.resolvedType as Type.Struct).name
                val stDef = structs[tName]!!
                val idx = stDef.fields.indexOfFirst { it.first == e.member }

                val stType = LLVMGetTypeByName(module, tName)!!
                val gep = LLVMBuildStructGEP2(builder, stType, base, idx.toUInt(), "fgep")
                LLVMBuildLoad2(builder, mapType(stDef.fields[idx].second), gep, "fload")!!
            } else throw RuntimeException("Access on r-value not impl")
        }
        else -> throw RuntimeException("Expr not impl")
    }

    private fun handleCall(e: Call): LLVMValueRef = memScoped {
        var fn = LLVMGetNamedFunction(module, e.name)
        var isExtension = false

        if (fn == null && e.args.isNotEmpty()) {
            val firstT = e.args[0].resolvedType
            if (firstT is Type.Struct) {
                fn = LLVMGetNamedFunction(module, "${firstT.name}_${e.name}")
                if (fn != null) isExtension = true
            }
        }

        if (fn == null) throw RuntimeException("Fn ${e.name} not found")

        val finalArgs = e.args.mapIndexed { i, arg ->
            if (isExtension && i == 0) {
                if (arg is VarRef) {
                    val v = scopes.first { it.containsKey(arg.name) }[arg.name]!!
                    if (LLVMGetTypeKind(v.type) == LLVMPointerTypeKind) {
                        LLVMBuildLoad2(builder, v.type, v.alloca, "self_ptr_load")!!
                    } else v.alloca
                } else {
                    val valRef = genExpr(arg)
                    val spill = LLVMBuildAlloca(builder, LLVMTypeOf(valRef), "spill")!!
                    LLVMBuildStore(builder, valRef, spill)
                    spill
                }
            } else genExpr(arg)
        }

        LLVMBuildCall2(builder, LLVMGlobalGetValueType(fn), fn, finalArgs.toCValues(), finalArgs.size.toUInt(),
            if (LLVMGetTypeKind(LLVMGetReturnType(LLVMGlobalGetValueType(fn))) == LLVMVoidTypeKind) "" else "call_res")!!
    }

    fun emitToFile(path: String) {
        val err = nativeHeap.alloc<CPointerVar<ByteVar>>()
        if (LLVMVerifyModule(module, LLVMVerifierFailureAction.LLVMReturnStatusAction, err.ptr) == 1) {
            println("LLVM Verify Error: ${err.value?.toKString()}")
        }
        LLVMPrintModuleToFile(module, path, null)
    }
}