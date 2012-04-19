import java.io.*;
import java.util.*;

public class a {
  private static final int MAX = Integer.getInteger( "BULK_SIZE", 100 );
  private static final int K = Integer.getInteger( "K", 100 );
  public static void main( String [] args ) throws Exception {
    BufferedReader br = new BufferedReader( new InputStreamReader( System.in ) );
    long [] samples = new long[ MAX ];
    QDigest qdigest = new QDigest( K );
    QDigest qdigest1 = new QDigest( K );

    QDigest t = new QDigest( K );
    QDigest t1 = new QDigest( K );

    List<Long> ss = new ArrayList<Long>();

    for( int i = 0;; ++i ) {
      String s=br.readLine();
      if ( i == MAX || s == null ) {
        long t0 = System.nanoTime();
        qdigest.addSamples( samples, 0, i );
        t0 = System.nanoTime() - t0;
        t.addSamples( new long[]{ t0 / i }, 0, 1 );
        i = 0;
      }

      if ( s == null ) break;

      samples[i] = Long.parseLong( s );
      assert samples[i] > 0: "assuming positive duration";

      long t0 = System.nanoTime();
      qdigest1.addSamples( samples, i, i+1 );
      t0 = System.nanoTime() - t0;
      t1.addSamples( new long[]{ t0 }, 0, 1 );

      ss.add( samples[i] );
    }

    Long [] sss = ss.toArray(new Long[0]);
    Arrays.sort( sss );
    ss = Arrays.asList( sss );

    for( int i = 0; i <= 100; ++i ) {
      System.out.println( i + "%: \t" + qdigest.pctile( i * 0.01 ) + "\t" + qdigest1.pctile( i * 0.01 ) + "\t" + pctile( i * 0.01, ss ) + "\t(" + t.pctile( i * 0.01 ) + " " + t1.pctile( i * 0.01 ) + ")" );
    }
    System.out.println( "digest size: \t" + qdigest.size() + "\t" + qdigest1.size() );
  }

  private static long pctile( double pct, List<Long> samples ) {
    return samples.get( Math.min( samples.size()-1, (int)(samples.size() * pct) ) );
  }

  public static class QDigest {
    private static class Node {
      long b;
      long c;
      public Node( long begin, long count ) {
        b = begin;
        c = count;
      }
    }

    ArrayList<Node> [] qdigest = (ArrayList<Node>[])new ArrayList[64];
    int k;
    long n;
    public QDigest( int k ) {
      this.k=k;
      for( int i=0; i < qdigest.length; ++i ) qdigest[i]=new ArrayList<Node>(0);
    }

    public void addSamples( long [] samples, int from, int to ) {
      if ( from >= to ) return;

      Arrays.sort( samples, from, to );
      ArrayList<Node> children = new ArrayList<Node>( to - from );
      n += to - from;
      long threshold = n / k;
      int cc = 0;
      for( int i = from; i < to - 1; ++i ) {
        ++cc;
        if ( samples[i] < samples[i+1] ) {
          children.add( new Node( samples[i], cc ) );
          cc=0;
        }
      }

      children.add( new Node( samples[to-1], cc+1 ) );
      qdigest[0] = zip( children, qdigest[0] );

      for(int i=0; i < qdigest.length-1; ++i ) {
        children=qdigest[i];
        ArrayList<Node> parents=qdigest[i+1];
        for( int c = 0, p = 0; c < children.size(); ) {
          Node self = children.get(c);
          assert self.b >= 0: "expecting all times to be non-negative";
          long expected_parent = self.b & (-2L << i); // parent starts at the same time as the left sibling
          assert expected_parent >= 0: "expecting all times to be non-negative: " + Long.toString( self.b, 16 ) + " & " + Long.toString( -2L << i, 16 ) + " = " + Long.toString( expected_parent, 16 );

          Node parent;
          for(;;) {
            parent = p < parents.size() && parents.get(p).b <= expected_parent ? parents.get(p): null;
            if ( parent == null || parent.b == expected_parent ) break;
            ++p;
          }

          Node peer = self.b == expected_parent && c < children.size()-1 && children.get(c+1).b == self.b+(1L << i) ? children.get(c+1): null;

          long count = self.c + (peer == null ? 0: peer.c) + (parent == null ? 0: parent.c);
          assert count > 0: "expect counters never negate";
          if ( count >= threshold ) c += peer == null ? 1: 2; // keep both children
          else { // aggregate
            children.remove( c );
            if ( peer != null ) children.remove( c ); // if peer existed, remove it too
            if ( parent == null ) {
              parent = self;
              parent.b = expected_parent;
              parents.add( p, parent );
            }
            parent.c = count;
            ++p; // next child won't belong to the same parent - both peers were removed
          }
        }
      }
    }

    public long pctile( double pct ) {
      int [] c = new int[ qdigest.length ];
      long t = Long.MIN_VALUE;
      long threshold = (int)(n*pct);

      if ( threshold <= 0 ) {
        if ( n == 0 ) return Long.MIN_VALUE;

        int min;
        for( min = 0; qdigest[min].size() == 0; ++min );
        t = qdigest[min].get(0).b;

        for( int i = min+1; i < qdigest.length; ++i ) {
          if ( qdigest[i].size() == 0 ) continue;
          Node p = qdigest[i].get(0);
          if ( t > p.b ) {
            min = i;
            t = p.b;
            assert p.b >= 0: "expect all time differences are non-negative";
          }
        }

        return t;
      }

      if ( threshold >= n ) {
        int max;
        for( max = qdigest.length - 1; qdigest[max].size() == 0; --max );
        return qdigest[max].get(qdigest[max].size()-1).b + (1L << max) - 1;
      }

      int count = 0;
      while( count < threshold ) {
        int min;
        for( min = 0; c[min] >= qdigest[min].size(); ++min );

        Node m = qdigest[min].get(c[min]);
        t = m.b+(1L << min)-1;
        assert m.b >= 0: "expect all time differences are non-negative";

        for( int i = min+1; i < qdigest.length; ++i ) {
          if ( c[i] >= qdigest[i].size() ) continue;
          Node p = qdigest[i].get(c[i]);
          if ( t > p.b+(1L << i)-1 ) {
            min = i;
            m = p;
            t = m.b + (1L << min)-1;
            assert m.b >= 0: "expect all time differences are non-negative";
          }
        }
        ++c[min];
        count += m.c;
      }

      return t;
    }

    public int size() {
      int sum = 0;
      for( ArrayList a: qdigest ) sum += a.size();
      return sum;
    }

    private static ArrayList<Node> zip( ArrayList<Node> l, ArrayList<Node> r ) {
      int le = l.size();
      int re = r.size();
      ArrayList<Node> zipped = new ArrayList<Node>( le + re );
      int i = 0, j = 0;
      while( i < le && j < re )
        if ( l.get(i).b < r.get(j).b ) zipped.add( l.get(i++) );
        else if ( r.get(j).b < l.get(i).b ) zipped.add( r.get(j++) );
        else {
          assert l.get(i).b == r.get(j).b;
          r.get(j).c += l.get(i++).c;
          zipped.add( r.get(j++) );
        }

      // only one of these loops will execute
      for(; i < le; ++i ) zipped.add( l.get(i) );
      for(; j < re; ++j ) zipped.add( r.get(j) );

      return zipped;
    }
  }
}