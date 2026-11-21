typedef size_t srep_idx;                 // Index of dict[] element

// In-memory match search engine
struct DictionaryCompressor
{
  srep_idx   L;            // Size of rolling hash AND number of positions among those we are looking for "local maxima"
                        //   (these may be different numbers in other implementations)
  srep_idx   MIN_MATCH;    // Minimum length of produced matches
  srep_idx   BASE_LEN;     // Minimum match length over all compress() procedures, employed by ENCODE_LZ_MATCH

  Offset  dictsize;     // Dictionary size, in bytes
  Offset  hashsize;     // Hash table size, in bytes

  srep_idx  *hasharr;      // Hash table
  srep_idx   hashmask;     // Hash table srep_idx mask

  Offset  BLOCKSIZE;
  int     BUFFERS;

  int     errcode;      // Non-zero if anything gone wrong

  DictionaryCompressor (Offset _dictsize, Offset _hashsize, srep_idx _MIN_MATCH, srep_idx _L, srep_idx _BASE_LEN, Offset _BLOCKSIZE, int _BUFFERS, LPType LargePageMode)
    : dictsize(_dictsize), MIN_MATCH(_MIN_MATCH), L(_L), BASE_LEN(_BASE_LEN), BLOCKSIZE(_BLOCKSIZE), BUFFERS(_BUFFERS)
  {
    errcode = NO_ERRORS;  hashsize = 0;  hasharr = NULL;
    if (!dictsize)   return;
    hashsize = roundup_to_power_of(_hashsize? _hashsize : min_hash_size(sizeof(*hasharr)*(dictsize/L)), 2);
    hashmask = hashsize/sizeof(*hasharr)-1;
    hasharr  = (srep_idx*) BigAlloc (hashsize, LargePageMode);
    if (!hasharr)   {errcode = ERROR_MEMORY; return;}
    my_memset (hasharr, 0, hashsize);
  }
  Offset memreq()          {return hashsize;}
  ~DictionaryCompressor()  {BigFree(hasharr);}

  void prepare_buffer (srep_idx* hashlist, char *buf, srep_idx bufsize);
  void compress (char *dict, char *buf, srep_idx bufsize, srep_idx *hashptr, unsigned &literal_bytes, STAT *statbuf, STAT *&stat);
};

// Находит адрес начала совпадения, идя назад от *p и *q
static inline char* find_match_start (char* p, char* q, char* start)
{
    while (q>start)   if (*--p != *--q)  return q+1;
    return q;
}

// Находит адрес первого несовпадающего байта, идя вперёд от *p и *q
static inline char* find_match_end (char* p, char* q, char* end)
{
    while (q<end && *p==*q) p++,q++;
    return q;
}

// Prepare buffer for the subsequent compression. Performed once for every block read.
// Stores indexes of L-byte blocks starting at buf[0]..buf[bufsize-L] to the hashptr[]
void DictionaryCompressor::prepare_buffer (srep_idx* hashptr, char *buf, srep_idx bufsize)
{
  if (!dictsize)   return;
  srep_idx num_blocks = bufsize/L;        // Number of *whole* L-byte blocks in the buffer. We need at least two blocks to srep_idx anything
  if (num_blocks > 1)
  {
    PolynomialRollingHash<srep_idx>  hash (buf, L, PRIME1);
    char* ptr = buf;

    // Split buffer into L-byte blocks and store maximal hash from every block and its position to hashptr[]
    for (srep_idx block=1; block<num_blocks; block++)
    {
      srep_idx maxhash = hash, maxi = 0;
      for (srep_idx i=0; i<L; i++, ptr++)
      {
        if unlikely(hash > maxhash)
          maxhash = hash, maxi = i;
        hash.update (ptr[0], ptr[L]);
      }
      *hashptr++ = maxhash & hashmask;
      *hashptr++ = maxi;
    }
  }
}

void DictionaryCompressor::compress (char *dict, char *buf, srep_idx bufsize, srep_idx *hashptr, unsigned &literal_bytes, STAT *statbuf, STAT *&stat)
{
  literal_bytes = bufsize;                      // literal_bytes are computed as the size of entire buffer minus length of all matches
  stat = statbuf;                               // where to save next found match info
  if (!dictsize)   return;

  srep_idx bufstart       = buf-dict;
  srep_idx bufend         = bufstart+bufsize;
  srep_idx last_match_end = bufstart;              // points to the end of last match written, we shouldn't start new match before it
  srep_idx DataStart      = (bufstart+BUFFERS*BLOCKSIZE)%dictsize;  // first byte that may be included in match because its data was not yet overwritten by the b/g read cycle

  // ОСНОВНОЙ ЦИКЛ, НАХОДЯЩИЙ ПОВТОРЯЮЩИЕСЯ СТРОКИ ВО ВХОДНЫХ ДАННЫХ
  for (srep_idx last_i=bufstart; last_i+2*L<=bufend; last_i+=L)
  {
    // Проверяем совпадение в лучшем на следующие L байт блоке тоже длины L
    srep_idx hash = *hashptr++;                    // Считываем хеш и адрес этого блока, уже найденные в prepare_buffer()
    srep_idx i    = last_i + (*hashptr++);
    if (i >= last_match_end)                    // Проверяем совпадение только если предыдущее найденное совпадение уже кончилось
    {
      srep_idx match = hasharr[hash];
      if (match)
      {
        // Возможно 6 конфигураций взаимного порядка DataStart, match и i.
        // Первые три из них соответствуют случаям, когда match попадает на область, перезаписываемую новыми данными, то есть он уже устарел
        if (match>=i && (DataStart<i || DataStart>match))  goto no_match;
        if (match<i  &&  DataStart<i && DataStart>match)   goto no_match;
        // В оставшихся трёх конфигурациях важно проследить, чтобы ни match, ни i в процессе сравнения не вышли за область актуальных данных.
        // Для i это сделать просто: last_match_end<=i<bufend.
        // Для match ограничение: LowBound_for_match<=match<dictsize,
        // где LowBound_for_match = match>=DataStart? DataStart : 0
        // и соответственно...
        // Наименьшее/наибольшее значение, которое может принимать при поиске
        // индекс базирующийся на i, чтобы индекс базирующийся на match,
        // не вышел за пределы буфера и не заглянул в будущие данные
        srep_idx LowBound  = match>=DataStart? (match-DataStart>i? 0 : i-(match-DataStart)) : i-match;
        srep_idx HighBound = match<i? dictsize : dictsize-match+i;
        // Найдём реальные начало и конец совпадения, сравнивая вперёд и назад от dict[i] <=> dict[match]
        // i ограничено снизу и сверху значениями last_match_end и bufend, соответственно
        srep_idx start = find_match_start (dict+match, dict+i, dict+mymax(last_match_end,LowBound)) - dict;
        srep_idx end   = find_match_end   (dict+match, dict+i, dict+mymin(bufend,HighBound)) - dict;
        // start и end - границы совпадения вокруг i, match_len - его длина, lit_len - расстояние от конца предыдущего матча до начала этого
        srep_idx match_len = end-start,  lit_len = start-last_match_end;
        if (match_len >= MIN_MATCH)
        {
          Offset match_distance  =  match<i? i-match : dictsize-match+i;
          // Совпадение найдено! Запишем информацию о нём в выходные буфера
          ENCODE_LZ_MATCH(stat,false,BASE_LEN, lit_len,match_distance,match_len);
          // Запомнить позицию конца найденного совпадения и вывести отладочную статистику
          debug ((match_cnt++, matches+=match_len));
          debug (verbose>1 && printf ("Match %d %d %d  (lit %d)\n", -match_distance, start, match_len, lit_len));
          literal_bytes -= match_len;  last_match_end=end;
        }
      }
    }
no_match:
    hasharr[hash] = i;         // Заносим в хеш-таблицу этот L-байтный блок
  }
}
