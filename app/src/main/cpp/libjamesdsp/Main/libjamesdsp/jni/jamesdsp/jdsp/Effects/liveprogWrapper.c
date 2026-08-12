void NSEEL_HOSTSTUB_EnterMutex() { }
void NSEEL_HOSTSTUB_LeaveMutex() { }
#include "../jdsp_header.h"
#include <ctype.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef __ANDROID__
#include <android/log.h>
#define LPFORENSIC(...) __android_log_print(ANDROID_LOG_ERROR, "LPFORENSIC", __VA_ARGS__)
#else
#define LPFORENSIC(...) ((void)0)
#endif

enum
{
	LIVEPROG_SECTION_NONE = -1,
	LIVEPROG_SECTION_INIT,
	LIVEPROG_SECTION_SLIDER,
	LIVEPROG_SECTION_BLOCK,
	LIVEPROG_SECTION_SAMPLE,
	LIVEPROG_SECTION_COUNT,
	LIVEPROG_SECTION_OTHER
};

typedef struct
{
	const char *start;
	size_t length;
	int present;
} LiveProgSourceSection;

LiveProg *LiveProgGetSlot(JamesDSPLib *jdsp, int slot)
{
	if (!jdsp || slot < 0 || slot > JDSP_LIVEPROG_EXTRA)
		return 0;
	if (slot == 0)
		return &jdsp->eel;
	return &jdsp->eelExtra[slot - 1];
}

static int LiveProgSectionAtLine(const char *line, const char *lineEnd)
{
	while (line < lineEnd && (*line == ' ' || *line == '\t'))
		line++;
	if (line >= lineEnd || *line != '@')
		return LIVEPROG_SECTION_NONE;

	const char *name = ++line;
	while (line < lineEnd && (isalnum((unsigned char)*line) || *line == '_'))
		line++;
	const size_t nameLength = (size_t)(line - name);
	if (!nameLength)
		return LIVEPROG_SECTION_NONE;

	while (line < lineEnd && (*line == ' ' || *line == '\t' || *line == '\r'))
		line++;
	if (line < lineEnd && !(line + 1 < lineEnd && line[0] == '/' && line[1] == '/'))
		return LIVEPROG_SECTION_NONE;

	if (nameLength == 4 && !strncmp(name, "init", nameLength))
		return LIVEPROG_SECTION_INIT;
	if (nameLength == 6 && !strncmp(name, "slider", nameLength))
		return LIVEPROG_SECTION_SLIDER;
	if (nameLength == 5 && !strncmp(name, "block", nameLength))
		return LIVEPROG_SECTION_BLOCK;
	if (nameLength == 6 && !strncmp(name, "sample", nameLength))
		return LIVEPROG_SECTION_SAMPLE;
	return LIVEPROG_SECTION_OTHER;
}

static int LiveProgSplitSource(const char *source, LiveProgSourceSection sections[LIVEPROG_SECTION_COUNT])
{
	memset(sections, 0, sizeof(LiveProgSourceSection) * LIVEPROG_SECTION_COUNT);
	const char *end = source + strlen(source);
	const char *line = source;
	int currentSection = LIVEPROG_SECTION_NONE;

	while (line < end)
	{
		const char *newLine = memchr(line, '\n', (size_t)(end - line));
		const char *lineEnd = newLine ? newLine : end;
		const int section = LiveProgSectionAtLine(line, lineEnd);
		if (section != LIVEPROG_SECTION_NONE)
		{
			if (currentSection >= 0 && currentSection < LIVEPROG_SECTION_COUNT)
				sections[currentSection].length = (size_t)(line - sections[currentSection].start);
			currentSection = LIVEPROG_SECTION_NONE;

			if (section >= 0 && section < LIVEPROG_SECTION_COUNT)
			{
				if (sections[section].present)
					return -6;
				sections[section].present = 1;
				sections[section].start = newLine ? newLine + 1 : end;
				currentSection = section;
			}
		}
		line = newLine ? newLine + 1 : end;
	}

	if (currentSection >= 0 && currentSection < LIVEPROG_SECTION_COUNT)
		sections[currentSection].length = (size_t)(end - sections[currentSection].start);
	return sections[LIVEPROG_SECTION_SAMPLE].present ? 1 : -2;
}

static char *LiveProgCopySection(const LiveProgSourceSection *section)
{
	if (!section->present)
		return 0;
	char *copy = (char*)calloc(section->length + 1, sizeof(char));
	if (copy && section->length)
		memcpy(copy, section->start, section->length);
	return copy;
}

static void LiveProgClearCode(LiveProg *pg)
{
	if (!pg)
		return;
	if (pg->codehandleProcess)
		NSEEL_code_free(pg->codehandleProcess);
	if (pg->codehandleBlock)
		NSEEL_code_free(pg->codehandleBlock);
	if (pg->codehandleSlider)
		NSEEL_code_free(pg->codehandleSlider);
	if (pg->codehandleInit)
		NSEEL_code_free(pg->codehandleInit);
	pg->codehandleInit = 0;
	pg->codehandleSlider = 0;
	pg->codehandleBlock = 0;
	pg->codehandleProcess = 0;
}

static void LiveProgDestroyState(LiveProg *pg)
{
	if (!pg)
		return;
	if (pg->vm)
	{
		LiveProgClearCode(pg);
		NSEEL_VM_free(pg->vm);
	}
	memset(pg, 0, sizeof(*pg));
}

static int LiveProgInitializeState(LiveProg *pg, float sampleRate)
{
	if (!pg)
		return 0;
	memset(pg, 0, sizeof(*pg));
	pg->active = 1;
	pg->vm = NSEEL_VM_alloc();
	if (!pg->vm)
		return 0;
	pg->vmFs = NSEEL_VM_regvar(pg->vm, "srate");
	pg->samplesBlock = NSEEL_VM_regvar(pg->vm, "samplesblock");
	pg->input1 = NSEEL_VM_regvar(pg->vm, "spl0");
	pg->input2 = NSEEL_VM_regvar(pg->vm, "spl1");
	if (!pg->vmFs || !pg->samplesBlock || !pg->input1 || !pg->input2)
	{
		LiveProgDestroyState(pg);
		return 0;
	}
	*pg->vmFs = sampleRate;
	*pg->samplesBlock = 0.0f;
	return 1;
}

void LiveProgConstructorSlot(JamesDSPLib *jdsp, int slot)
{
	LiveProg *pg = LiveProgGetSlot(jdsp, slot);
	if (pg)
		LiveProgInitializeState(pg, jdsp->fs);
}

void LiveProgDestructorSlot(JamesDSPLib *jdsp, int slot)
{
	LiveProg *pg = LiveProgGetSlot(jdsp, slot);
	if (pg)
		LiveProgDestroyState(pg);
}

void LiveProgConstructor(JamesDSPLib *jdsp)
{
	LiveProgConstructorSlot(jdsp, 0);
	for (int i = 1; i <= JDSP_LIVEPROG_EXTRA; i++)
		LiveProgConstructorSlot(jdsp, i);
}

void LiveProgDestructor(JamesDSPLib *jdsp)
{
	LiveProgDestructorSlot(jdsp, 0);
	for (int i = 1; i <= JDSP_LIVEPROG_EXTRA; i++)
		LiveProgDestructorSlot(jdsp, i);
}

void LiveProgEnableSlot(JamesDSPLib *jdsp, int slot)
{
	LiveProg *pg = LiveProgGetSlot(jdsp, slot);
	if (!pg)
		return;
	const int enable = pg->vmFs && pg->compileSucessfully;
	if (enable)
		*pg->vmFs = jdsp->fs;
	if (slot == 0)
		jdsp->liveprogEnabled = enable;
	else
		jdsp->liveprogExtraEnabled[slot - 1] = enable;
}

void LiveProgDisableSlot(JamesDSPLib *jdsp, int slot)
{
	if (!LiveProgGetSlot(jdsp, slot))
		return;
	if (slot == 0)
		jdsp->liveprogEnabled = 0;
	else
		jdsp->liveprogExtraEnabled[slot - 1] = 0;
}

void LiveProgEnable(JamesDSPLib *jdsp)
{
	LiveProgEnableSlot(jdsp, 0);
}

void LiveProgDisable(JamesDSPLib *jdsp)
{
	LiveProgDisableSlot(jdsp, 0);
}

static int LiveProgLoadCode(LiveProg *pg, float sampleRate,
	const char *codeTextInit, const char *codeTextSlider,
	const char *codeTextBlock, const char *codeTextProcess)
{
	if (!pg || !pg->vm || !codeTextProcess)
		return -7;
	pg->compileSucessfully = 0;
	compileContext *ctx = (compileContext*)pg->vm;
	LiveProgClearCode(pg);
	NSEEL_VM_freevars(pg->vm);
	NSEEL_init_memRegion(pg->vm);
	memset(ctx->ram_state, 0, sizeof(ctx->ram_state));
	pg->vmFs = NSEEL_VM_regvar(pg->vm, "srate");
	pg->samplesBlock = NSEEL_VM_regvar(pg->vm, "samplesblock");
	pg->input1 = NSEEL_VM_regvar(pg->vm, "spl0");
	pg->input2 = NSEEL_VM_regvar(pg->vm, "spl1");
	if (!pg->vmFs || !pg->samplesBlock || !pg->input1 || !pg->input2)
		return -7;
	*pg->vmFs = sampleRate;
	*pg->samplesBlock = 0.0f;
	ctx->functions_common = 0;

	if (codeTextInit && *codeTextInit)
	{
		pg->codehandleInit = NSEEL_code_compile_ex(pg->vm, codeTextInit, 0,
			NSEEL_CODE_COMPILE_FLAG_COMMONFUNCS | NSEEL_CODE_COMPILE_FLAG_COMMONFUNCS_RESET);
		if (!pg->codehandleInit)
			return -1;
	}
	if (codeTextSlider && *codeTextSlider)
	{
		pg->codehandleSlider = NSEEL_code_compile(pg->vm, codeTextSlider, 0);
		if (!pg->codehandleSlider)
			return -4;
	}
	if (codeTextBlock && *codeTextBlock)
	{
		pg->codehandleBlock = NSEEL_code_compile(pg->vm, codeTextBlock, 0);
		if (!pg->codehandleBlock)
			return -5;
	}
	pg->codehandleProcess = NSEEL_code_compile(pg->vm, codeTextProcess, 0);
	if (!pg->codehandleProcess)
		return -3;

	if (pg->codehandleInit)
		NSEEL_code_execute(pg->codehandleInit);
	if (pg->codehandleSlider)
		NSEEL_code_execute(pg->codehandleSlider);
	pg->compileSucessfully = 1;
	return 1;
}

const char* checkErrorCode(int errCode)
{
	switch (errCode)
	{
	case -1:
		return "Syntax error at @init section";
	case -2:
		return "@sample section not found";
	case -3:
		return "Syntax error at @sample section";
	case -4:
		return "Syntax error at @slider section";
	case -5:
		return "Syntax error at @block section";
	case -6:
		return "Duplicate LiveProg section";
	case -7:
		return "Failed to allocate or initialize LiveProg state";
	default:
		return "No syntax errors detected";
	}
}

int LiveProgStringParserSlot(JamesDSPLib *jdsp, int slot, char *eelCode,
	char *errorBuffer, size_t errorBufferSize)
{
	if (errorBuffer && errorBufferSize)
		errorBuffer[0] = '\0';
	if (!jdsp || !eelCode || !LiveProgGetSlot(jdsp, slot))
		return -7;

	LiveProgSourceSection sections[LIVEPROG_SECTION_COUNT];
	int errorMsg = LiveProgSplitSource(eelCode, sections);
	char *codeText[LIVEPROG_SECTION_COUNT] = { 0 };
	if (errorMsg > 0)
	{
		for (int i = 0; i < LIVEPROG_SECTION_COUNT; i++)
		{
			codeText[i] = LiveProgCopySection(&sections[i]);
			if (sections[i].present && !codeText[i])
			{
				errorMsg = -7;
				break;
			}
		}
	}

	if (errorMsg > 0)
	{
		LiveProg candidate;
		if (!LiveProgInitializeState(&candidate, jdsp->fs))
			errorMsg = -7;
		else
		{
			jdsp_lock(jdsp);
			errorMsg = LiveProgLoadCode(&candidate, jdsp->fs,
				codeText[LIVEPROG_SECTION_INIT],
				codeText[LIVEPROG_SECTION_SLIDER],
				codeText[LIVEPROG_SECTION_BLOCK],
				codeText[LIVEPROG_SECTION_SAMPLE]);
			if (errorMsg > 0)
			{
				LiveProg *target = LiveProgGetSlot(jdsp, slot);
				LiveProg previous = *target;
				candidate.active = previous.active;
				*target = candidate;
				memset(&candidate, 0, sizeof(candidate));
				LiveProgDestroyState(&previous);
			}
			else
			{
				const char *error = NSEEL_code_getcodeerror(candidate.vm);
				if (error && errorBuffer && errorBufferSize)
					snprintf(errorBuffer, errorBufferSize, "%s", error);
			}
			LiveProgDestroyState(&candidate);
			jdsp_unlock(jdsp);
		}
	}

	for (int i = 0; i < LIVEPROG_SECTION_COUNT; i++)
		free(codeText[i]);
	return errorMsg;
}

int LiveProgStringParser(JamesDSPLib *jdsp, char *eelCode,
	char *errorBuffer, size_t errorBufferSize)
{
	return LiveProgStringParserSlot(jdsp, 0, eelCode, errorBuffer, errorBufferSize);
}

typedef struct
{
	const char *name;
	float *value;
} LiveProgVariableLookup;

static int32_t LiveProgFindVariableCallback(const char *name, float *value, void *userctx)
{
	LPFORENSIC("CALLBACK_ENTRY name=%p value=%p userctx=%p", (void*)name, (void*)value, userctx);
	LiveProgVariableLookup *lookup = (LiveProgVariableLookup*)userctx;
	LPFORENSIC("CALLBACK_LOOKUP lookup=%p lookup_name=%p", (void*)lookup, lookup ? (void*)lookup->name : 0);
	if (!lookup || !name || !value)
		return 1;
	LPFORENSIC("BEFORE_STRCMP n=%s requested=%s", name, lookup->name);
	int result = strcmp(name, lookup->name);
	LPFORENSIC("AFTER_STRCMP result=%d", result);
	if (!result)
	{
		LPFORENSIC("MATCH_FOUND value=%p", (void*)value);
		LPFORENSIC("BEFORE_STORE_LOOKUP_POINTER");
		lookup->value = value;
		LPFORENSIC("AFTER_STORE_LOOKUP_POINTER");
		LPFORENSIC("RETURN_FROM_CALLBACK");
		return 0;
	}
	LPFORENSIC("RETURN_FROM_CALLBACK");
	return 1;
}

static float *LiveProgFindVariable(LiveProg *pg, const char *name)
{
	if (!pg || !pg->vm || !name)
		return 0;
	LiveProgVariableLookup lookup = { name, 0 };
	LPFORENSIC("FIND_ENTRY pg=%p vm=%p name=%p lookup=%p lookup_name=%p", (void*)pg, pg->vm, (void*)name, (void*)&lookup, (void*)lookup.name);
	LPFORENSIC("BEFORE_ENUMALLVARS");
	NSEEL_VM_enumallvars(pg->vm, LiveProgFindVariableCallback, &lookup);
	LPFORENSIC("AFTER_ENUMALLVARS value=%p", (void*)lookup.value);
	if (!lookup.value)
		LPFORENSIC("LOOKUP_VALUE_NULL");
	return lookup.value;
}

int LiveProgSetVariableSlot(JamesDSPLib *jdsp, int slot, const char *name, float value)
{
	LPFORENSIC("SETTER_ENTRY jdsp=%p slot=%d name=%p name_text=%s value=%f", (void*)jdsp, slot, (void*)name, name ? name : "<null>", value);
	if (!jdsp || !name || !*name || !isfinite(value))
		return 0;
	LPFORENSIC("BEFORE_STRLEN");
	const size_t nameLength = strlen(name);
	LPFORENSIC("AFTER_STRLEN len=%zu", nameLength);
	if (nameLength > NSEEL_MAX_VARIABLE_NAMELEN ||
		!(isalpha((unsigned char)name[0]) || name[0] == '_'))
		return 0;
	for (size_t i = 1; i < nameLength; i++)
		if (!(isalnum((unsigned char)name[i]) || name[i] == '_'))
			return 0;
	LPFORENSIC("AFTER_IDENTIFIER_VALIDATION");

	LPFORENSIC("BEFORE_LOCK");
	jdsp_lock(jdsp);
	LPFORENSIC("AFTER_LOCK");
	LPFORENSIC("BEFORE_GET_SLOT");
	LiveProg *pg = LiveProgGetSlot(jdsp, slot);
	LPFORENSIC("AFTER_GET_SLOT pg=%p", (void*)pg);
	if (pg)
		LPFORENSIC("STATE vm=%p compiled=%d active=%d init=%p slider=%p block=%p process=%p vmFs=%p samplesBlock=%p input1=%p input2=%p", pg->vm, pg->compileSucessfully, pg->active, pg->codehandleInit, pg->codehandleSlider, pg->codehandleBlock, pg->codehandleProcess, (void*)pg->vmFs, (void*)pg->samplesBlock, (void*)pg->input1, (void*)pg->input2);
	LPFORENSIC("BEFORE_FIND_VARIABLE");
	float *variable = pg && pg->compileSucessfully
		? LiveProgFindVariable(pg, name) : 0;
	LPFORENSIC("AFTER_FIND_VARIABLE variable=%p", (void*)variable);
	if (!variable)
	{
		jdsp_unlock(jdsp);
		return 0;
	}
	LPFORENSIC("BEFORE_VARIABLE_WRITE alignment=%zu", (size_t)((uintptr_t)variable & 3));
	*variable = value;
	LPFORENSIC("AFTER_VARIABLE_WRITE");
	if (pg->codehandleSlider)
	{
		LPFORENSIC("BEFORE_SLIDER_EXECUTE");
		NSEEL_code_execute(pg->codehandleSlider);
		LPFORENSIC("AFTER_SLIDER_EXECUTE");
	}
	LPFORENSIC("BEFORE_UNLOCK");
	jdsp_unlock(jdsp);
	LPFORENSIC("AFTER_UNLOCK");
	LPFORENSIC("SETTER_RETURN");
	return 1;
}

int LiveProgSetVariable(JamesDSPLib *jdsp, const char *name, float value)
{
	return LiveProgSetVariableSlot(jdsp, 0, name, value);
}

void LiveProgProcessSlot(JamesDSPLib *jdsp, int slot, size_t n)
{
	LiveProg *eel = LiveProgGetSlot(jdsp, slot);
	if (!eel || !eel->compileSucessfully || !eel->active)
		return;

	*eel->samplesBlock = (float)n;
	if (eel->codehandleBlock)
		NSEEL_code_execute(eel->codehandleBlock);

	for (size_t i = 0; i < n; i++)
	{
		*eel->input1 = jdsp->tmpBuffer[0][i];
		*eel->input2 = jdsp->tmpBuffer[1][i];
		NSEEL_code_execute(eel->codehandleProcess);
		jdsp->tmpBuffer[0][i] = isfinite((float)*eel->input1) ? (float)*eel->input1 : 0.0f;
		jdsp->tmpBuffer[1][i] = isfinite((float)*eel->input2) ? (float)*eel->input2 : 0.0f;
	}
}

void LiveProgProcess(JamesDSPLib *jdsp, size_t n)
{
	LiveProgProcessSlot(jdsp, 0, n);
}