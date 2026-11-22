// to do: отбор файлов по именам ("name" или "dir/name"),
//        дешифрование данных/заголовка
//        добавление ".arc", listfiles/-ap/-kb

// Обработка сбоев при распаковке архива
#undef  ON_CHECK_FAIL
#define ON_CHECK_FAIL()   UnarcQuit()
void UnarcQuit();

// Доступ к структуре архива, парсингу командной строки и выполнению операций над архивом
#include "ArcStructure.h"
#include "ArcCommand.h"
#include "ArcProcess.h"

// Экстренный выход из программы в случае ошибки
void UnarcQuit()
{
  CurrentProcess->quit(FREEARC_ERRCODE_GENERAL);
}

#include "CUI.h"
CUI UI;

#ifdef __ANDROID__
#include <signal.h>
#include <unwind.h>
#include <dlfcn.h>
#include <unistd.h>
#include <errno.h>
static _Unwind_Reason_Code gi_bt (struct _Unwind_Context *c, void *arg)
{
  int *n = (int*)arg;
  uintptr_t pc = _Unwind_GetIP(c);
  if (pc) {
    Dl_info di;
    if (dladdr((void*)pc, &di) && di.dli_sname)
      fprintf(stderr, "  #%d %s+0x%lx\n", *n, di.dli_sname, (unsigned long)(pc - (uintptr_t)di.dli_saddr));
    else if (dladdr((void*)pc, &di))
      fprintf(stderr, "  #%d %s base+0x%lx\n", *n, di.dli_fname ? di.dli_fname : "?", (unsigned long)(pc - (uintptr_t)di.dli_fbase));
    else
      fprintf(stderr, "  #%d pc=0x%lx\n", *n, (unsigned long)pc);
  }
  (*n)++;
  return *n > 30 ? _URC_END_OF_STACK : _URC_NO_REASON;
}
static void gi_sig (int sig)
{
  int n = 0;
  fprintf(stderr, "\nGI-SIGNAL %d errno=%d\n", sig, errno);
  _Unwind_Backtrace(gi_bt, &n);
  fflush(stderr);
  _exit(128 + sig);
}
#endif

int main (int argc, char *argv[])
{
#ifdef __ANDROID__
  signal(SIGABRT, gi_sig); signal(SIGSEGV, gi_sig); signal(SIGBUS, gi_sig);
#endif
  setvbuf(stdout, NULL, _IOLBF, 0);   /* line-buffered output: nothing lost on abort */
  setvbuf(stderr, NULL, _IONBF, 0);
  UI.DisplayHeader (HEADER1 NAME);
  COMMAND command (argc, argv);    // Распарсить команду
  if (command.ok)                  // Если парсинг был удачен и можно выполнить команду
    PROCESS (&command, &UI);       //   Выполнить разобранную команду
  printf ("\n");
  return command.ok? EXIT_SUCCESS : FREEARC_EXIT_ERROR;
}
